# cloudlock
A locking wrapper that runs a program only when a lock is held.

## Basics

* Cloudlock uses AWS DynamoDB to track a lock.  
* When and only when it has the lock does it run the specified process.
* If it is unable to renew the lock and the lock expires, Cloudlock will stop the process.
* If the clock time is off by more than 40 seconds, Cloudlock will not run the process.
* When Cloudlock gets a new lock, it waits 'restart_gap_min' before starting the process.
* If Clocklock is restarted while holding the lock, it will immediately start the process
  (while still respecting 'restart_gap_min').

Configuration notes:

* Each instance should run with a different 'my_id'.  If two instances run with the same 'my_id'
  they will both run the process.
* The DynamoDB table can be used for many locks on different things, just use a different 'lock_label'
  for each different purpose.
* The longer the lock expiration time, the longer Cloudlock can run the process if it can't contact
  AWS.  But the longer the lock expiration time, the longer before another node can get the lock.


## Setup

### AWS Setup

* Create an Dyanamo DB table.  Note the region and table name.  When you create the table,
  set the primary key as "label" as type String.
* Create an IAM user and give it permissions to get, update and put item on the table.

Here is an example policy:

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "VisualEditor0",
            "Effect": "Allow",
            "Action": [
                "dynamodb:PutItem",
                "dynamodb:DeleteItem",
                "dynamodb:GetItem",
                "dynamodb:UpdateItem",
                "dynamodb:GetRecords"
            ],
            "Resource": "arn:aws:dynamodb:us-west-2:123456789012:table/cloudlock"
        }
    ]
}
```

Currently the code only uses PutItem and GetItem, but some future code might switch some actions to UpdateItem.

The ARN of your table should be the resource rather than the example above.


### Required environment variables
```
export cloudlock_aws_key_id=A....
export cloudlock_aws_secret=SECRET
export cloudlock_dynamodb_table=cloudlock
export cloudlock_aws_region=us-west-2
export cloudlock_lock_label=testlock
export cloudlock_my_id=node1
```

### Optional environment variables

Shown here with defaults

```
export cloudlock_restart_gap_min=30
export cloudlock_lock_expiration_min=30
export cloudlock_renew_time_min=2
```

### Operation

Once the table is setup, cloudlock is used by running it with the above environment variables set and
the command to run when the lock is held passed as parameters.

### Lighthouse

To use Cloudlock with Eth2 Lighthouse, see https://github.com/fireduck64/eth2stake
There will be notes there.



