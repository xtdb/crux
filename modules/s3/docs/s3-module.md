# S3 Module

[Amazon's S3](https://aws.amazon.com/s3/) can be used as an XTDB object store.

For an overview of the operation and requirements of the XTDB S3 module, see the [S3 reference](/reference/main/modules/s3).

## Configuration:

To configure it, add the following to your node options map:

```clojure
{:xtdb.s3/object-store <opts>}
```

* `configurator` ([`S3Configurator`](/sdks/java/xtdb/s3/S3Configurator.html), optional): class to build S3 requests
* `bucket` (String, required): S3 bucket to store the objects in.
* `prefix` (String, optional): directory prefix within the bucket.
* `sns-topic-arn` (String, required): [SNS](https://aws.amazon.com/sns/) topic to listen to for newly created objects.

## Authentication:

Authentication is done through any of the standard Java AWS SDK authentication methods.
