import datetime
import json
import os

import botocore


def list_s3_objects_from(s3_client, bucket, prefix=""):
    """
    Generates all the objects under a given bucket/prefix.
    """
    os.makedirs("_cache", exist_ok=True)
    out_path = f"_cache/s3--{bucket}--{prefix.replace('/', '-')}.json"

    try:
        for line in open(out_path):
            s3_obj = json.loads(line)
            s3_obj["LastModified"] = datetime.datetime.fromisoformat(
                s3_obj["LastModified"]
            )
            yield s3_obj
    except FileNotFoundError:
        paginator = s3_client.get_paginator("list_objects_v2")
        tmp_path = out_path + ".tmp"

        with open(tmp_path, "w") as cache_file:
            for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
                for s3_obj in page["Contents"]:
                    yield s3_obj
                    s3_obj["LastModified"] = s3_obj["LastModified"].isoformat()
                    cache_file.write(json.dumps(s3_obj) + "\n")

        os.rename(tmp_path, out_path)


def get_s3_object(s3_client, bucket, key):
    """
    Retrieves the contents of an object from S3.
    """
    out_path = os.path.join(f"_cache", bucket, key)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    try:
        return open(out_path, "rb")
    except FileNotFoundError:
        print(f"Downloading s3://{bucket}/{key}")
        s3_client.download_file(Bucket=bucket, Key=key, Filename=out_path)
        return open(out_path, "rb")


def get_s3_object_size(s3_client, s3_bucket, s3_key):
    """
    Retrieves the content length of an object from S3.
    """
    try:
        s3_head_object_response = s3_client.head_object(Bucket=s3_bucket, Key=s3_key)
    except botocore.exceptions.ClientError as e:
        if e.response['Error']['Code'] == "404":
            return None

    return s3_head_object_response["ContentLength"]
