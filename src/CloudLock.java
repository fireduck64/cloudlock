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

public class CloudLock
{
  // These things will come from config
  public static final long restart_gap = 25L * 60L * 1000L; // 25 minutes
  public static final long lock_expiration = 30L * 60L * 1000; // 30 min
  //public static final long lock_expiration = 60L * 1000; // 1 minute
  public static final long renew_time = 300L * 1000L; // 5 min
  //public static final long renew_time = 10L * 1000L; // 10 sec
  public static final String aws_key_id = "";
  public static final String aws_secret = "";
  public static final String dynamodb_table = "eth2-vc";
  public static final String aws_region = "us-west-2";
  public static final String lock_label = "testlock";



  public static void main(String args[]) throws Exception
  {
    new CloudLock();

  }

  private volatile LockInfo lock_info = null;

  public CloudLock()
  {
    new LockRenewThread().start();
    new LockCheckThread().start();
  }


  /**
   * Attempts to get the lock or renew the lock
   */
  public class LockRenewThread extends PeriodicThread
  {
    private AmazonDynamoDB dynamo;
    private final String my_id;

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

      //my_id = UUID.randomUUID().toString();
      my_id = "duck";


    }

    public void runPass()
    {
      GetItemResult curr_item = dynamo.getItem(dynamodb_table, ImmutableMap.of("label", new AttributeValue("lock_label")) , true);

      System.out.println("Existing lock: " + curr_item);
      System.out.println("My id: " + my_id);

      long new_expire_time = lock_expiration + System.currentTimeMillis();
      long new_start_time = System.currentTimeMillis();
      long effective_start_time = -1;

      Map<String, AttributeValue> put_map = new TreeMap<>();
      put_map.put("label", new AttributeValue("lock_label"));
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
            pir.addItemEntry("start_time",new AttributeValue().withN(""+new_start_time));
            System.out.println("Attempting to take over lock from " + other);
            effective_start_time = new_start_time;
          }
          else
          { // Not expired
            System.out.println(other + " has lock: " + new LockInfo(tm, old_start_time));
            return;
          }

        }

        // if lock is not owned by me, and is expired, delete it
      }
      

      dynamo.putItem(pir);
      lock_info = new LockInfo(new_expire_time, effective_start_time);
      System.out.println("New lock: " + lock_info);

    }
  }

  /**
   * Checks to make sure we have the lock, if not, kill child process
   */
  public class LockCheckThread extends PeriodicThread
  {
    public LockCheckThread()
    {
      super(20000L);
      setName("LockCheckThread");
      // Intentionally not making this a daemon thread, if this dies we want to exit


    }

    public void runPass()
    {

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

  
}
