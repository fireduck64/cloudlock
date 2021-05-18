package duckutil.cloudlock;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import duckutil.PeriodicThread;
import com.google.common.collect.ImmutableMap;
import java.util.UUID;
import java.util.TreeMap;
import java.util.Map;
import java.text.DecimalFormat;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import duckutil.NetUtil;


public class CloudLock
{
  public static final long EXPIRE_SHUTDOWN_MS=60000L; // Shutdown 60-seconds before lock expire
	public static final long CLOCK_SKEW_WARN_MS=20000L;
  public static final long CLOCK_SKEW_FAIL_MS=40000L;

  private static final Logger logger = Logger.getLogger("cloudlock");

  // These things will come from config
  public final long restart_gap;
  public final long lock_expiration;
  public final long renew_time;
  public final String aws_key_id;
  public final String aws_secret;
  public final String dynamodb_table;
  public final String aws_region;
  public final String lock_label;

  private final String my_id;
  private final String[] cmd_array;


  public static void main(String args[]) throws Exception
  {
    
    new CloudLock(args);

  }

  private volatile LockInfo lock_info = null;

  public CloudLock(String[] cmd_array)
    throws Exception
  {
    this.cmd_array = cmd_array;
    restart_gap = getTimeWithDefault("restart_gap_min", 30);
    lock_expiration = getTimeWithDefault("lock_expiration_min", 30);
    renew_time = getTimeWithDefault("renew_time_min", 2);
    aws_key_id = getRequired("aws_key_id");
    aws_secret = getRequired("aws_secret");
    dynamodb_table = getRequired("dynamodb_table");
    aws_region = getRequired("aws_region");
    lock_label = getRequired("lock_label");
    my_id = getRequired("my_id");


    new LockRenewThread().start();
    new LockCheckThread().start();

    while(true)
    {
      Thread.sleep(5000);
    }
  }

  public long getTimeWithDefault(String label, int default_min)
  {
    String k = "cloudlock_" + label;
    if (System.getenv().containsKey(k))
    {
      int min = Integer.parseInt(System.getenv().get(k));
      return 1000L * 60L * min;
    }
    return 1000L * 60L * default_min;

  }
  public String getRequired(String label)
  {
    String k = "cloudlock_" + label;
    if (!System.getenv().containsKey(k))
    {
      throw new RuntimeException("Missing required environment variable: " + k);
    }
    return System.getenv().get(k);

  }


  /**
   * Attempts to get the lock or renew the lock
   */
  public class LockRenewThread extends PeriodicThread
  {
    private AmazonDynamoDB dynamo;

    public LockRenewThread()
    {
      super(renew_time);
      setName("LockRenewThread");
      setDaemon(true);

      dynamo = AmazonDynamoDBClientBuilder
        .standard()
        .withCredentials(
          new AWSStaticCredentialsProvider(new BasicAWSCredentials(aws_key_id, aws_secret)))
        .withRegion(aws_region)
        .build();

    }

    @Override
    public void runPass()
      throws Exception
    {
      GetItemResult curr_item = dynamo.getItem(dynamodb_table, ImmutableMap.of("label", new AttributeValue(lock_label)) , true);

      logger.info("Existing lock: " + curr_item);

      if (!checkTime())
      {
        lock_info = null;
        return;
      }

      long new_expire_time = lock_expiration + System.currentTimeMillis();
      long new_start_time = System.currentTimeMillis();
      long effective_start_time = -1;

      Map<String, AttributeValue> put_map = new TreeMap<>();
      put_map.put("label", new AttributeValue(lock_label));
      put_map.put("holder", new AttributeValue(my_id));
      put_map.put("expire_time", new AttributeValue().withN("" + new_expire_time));
      put_map.put("version", new AttributeValue(UUID.randomUUID().toString()));



      PutItemRequest pir = new PutItemRequest(dynamodb_table, put_map);


      if (curr_item.getItem() == null)
      {
        pir.setConditionExpression("attribute_not_exists(holder)");
        pir.setConditionExpression("attribute_not_exists(version)");
        pir.addItemEntry("start_time",new AttributeValue().withN(""+new_start_time));
        effective_start_time = new_start_time;
      }
      else
      {
        long old_start_time = Long.parseLong(curr_item.getItem().get("start_time").getN());
      
        // This way, the put will only ever take effect if no other updates have happened
        pir.addExpectedEntry("version", new ExpectedAttributeValue(curr_item.getItem().get("version")));

        if (curr_item.getItem().get("holder").getS().equals(my_id))
        {
          // Owned by me, update expiration
          pir.addItemEntry("start_time",new AttributeValue().withN(""+old_start_time));
          effective_start_time = old_start_time;
        }
        else
        {
          // Not owned by me
          long tm = Long.parseLong(curr_item.getItem().get("expire_time").getN());
          String other = curr_item.getItem().get("holder").getS();

          if (tm < System.currentTimeMillis())
          { // Expired
            // if lock is not owned by me, and is expired, delete it
            pir.addItemEntry("start_time",new AttributeValue().withN(""+new_start_time));
            logger.info("Attempting to take over lock from " + other);
            effective_start_time = new_start_time;
          }
          else
          { // Not expired
            logger.info(other + " has lock: " + new LockInfo(tm, old_start_time));
            lock_info = null;
            return;
          }

        }

      }
      
      

      dynamo.putItem(pir);
      lock_info = new LockInfo(new_expire_time, effective_start_time);
      logger.info("Current lock: " + lock_info);
      if (!lock_info.afterStartWait())
      {
        long run_time = lock_info.start_time + restart_gap;
        long delta = run_time - System.currentTimeMillis();

        logger.info("  In start gap, will start process in " + timeToString(delta));
        
      }

    }
  }

  /**
   * Checks to make sure we have the lock, if not, kill child process
   */
  public class LockCheckThread extends PeriodicThread
  {
    boolean running = false;
    Process proc = null;

    public LockCheckThread()
    {
      super(5000L);
      setName("LockCheckThread");
      setDaemon(true);
      // Intentionally not making this a daemon thread, if this dies we want to exit
    }

    public void runPass()
      throws Exception
    {
      if (shouldRun())
      {
        if (running)
        {
          if (!proc.isAlive())
          {
            logger.info("Process exited");
            System.exit(proc.exitValue());
          }

        }
        else
        {
          startProcess();
        }
      }
      else
      {
        if (running)
        {
          if (!proc.isAlive())
          {
            running=false;
            proc=null;
          }
          else
          {
            LockInfo li=lock_info;
            if ((li==null) || (lock_info.isExpired()))
            {
              logger.warning("force killing");
              proc.destroyForcibly();
            }
            else
            {
              logger.warning("stopping");
              proc.destroy();
            }
          }

        }


      }

    }
    public void startProcess()
      throws Exception
    {
      logger.warning("Starting process");
      proc = Runtime.getRuntime().exec(cmd_array);
      new CopyThread(proc.getInputStream(), System.out).start();
      new CopyThread(proc.getErrorStream(), System.err).start();

      running=true;
    }

    public boolean shouldRun()
    {
      LockInfo li = lock_info;
      if (li == null) return false;
      if (li.expiring()) return false;
      if (li.isExpired()) return false;
      if (li.afterStartWait()) return true;

      return false;
    }
  }

  public class LockInfo
  {
    public final long expiration;
    public final long start_time;
    public LockInfo(long expiration, long start_time)
    {
      this.expiration = expiration;
      this.start_time = start_time;
    }

    @Override
    public String toString()
    {
      long expire_delta = expiration - System.currentTimeMillis();
      long start_delta = System.currentTimeMillis() - start_time;
      return String.format("Lock{expires in %s, started %s ago}", 
        timeToString(expire_delta), timeToString(start_delta));
    }

    public boolean isExpired()
    {
      return (System.currentTimeMillis() >= expiration);
    }
    public boolean expiring()
    {
      return (System.currentTimeMillis() + EXPIRE_SHUTDOWN_MS >= expiration);
    }
    public boolean afterStartWait()
    {
      return (start_time + restart_gap < System.currentTimeMillis());
    }

  }

  public static String timeToString(long ms)
  {
    long round_up = 0;
    if (ms % 1000 > 500) round_up=1;

    long t = ms / 1000 + round_up;
    
    long sec = t % 60;
    t = t / 60;
    long min = t % 60;
    t = t / 60;
    long hour = t % 24;
    t = t / 25;
    long day = t;

    DecimalFormat df=new DecimalFormat("00");

    StringBuilder sb = new StringBuilder();
    boolean print=false;
    if (print || (day != 0)) { sb.append(df.format(day) + "d"); print=true;}
    if (print || (hour != 0)) { sb.append(df.format(hour) + "h"); print=true;}
    if (print || (min != 0)) { sb.append(df.format(min) + "m"); print=true;}
    
    sb.append(df.format(sec) + "s");

    return sb.toString();

  }

  public class CopyThread extends Thread
  {
    final InputStream src;
    final OutputStream sink;

    public CopyThread(InputStream src, OutputStream sink)
    {
      this.src = src;
      this.sink = sink;
    }

    @Override
    public void run()
    {
      byte[] buff=new byte[8192];
      try
      {
      while(true)
      {
        int r = src.read(buff);
        if (r > 0)
        {
          sink.write(buff,0,r);
          sink.flush();
        }
        if (r < 0) return;
      }
      }
      catch(java.io.IOException e)
      {
        e.printStackTrace();
        System.exit(-1);
      }

    }


  }


  private boolean checkTime()
    throws Exception
  {
    long start = System.currentTimeMillis();

    long server = Long.parseLong(NetUtil.getUrlLine("https://timecheck.snowblossom.org/time"));

    long end = System.currentTimeMillis();

    long mid = (end + start) /2;

    long diff = Math.abs(server - mid);
		
    if (diff > CLOCK_SKEW_WARN_MS)
    {
      logger.log(Level.WARNING, String.format("Local clock seems to be off by %d ms", diff));
    }
    if (diff > CLOCK_SKEW_FAIL_MS)
    {
      return false;
    }
    return true;

  }



}
