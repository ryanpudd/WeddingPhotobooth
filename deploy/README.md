# Deploy: S3 upload bucket + IAM uploader user + IAM read-only user

`s3-photobooth-stack.yaml` creates everything the photobooth app's admin
settings screen needs to upload to S3, plus a separate way to browse/download
the photos afterward, all scoped as tightly as possible:

- A private S3 bucket (`ryanpudd-weddingphotos` by default) with all public
  access blocked, default server-side encryption (SSE-S3), ACLs disabled
  (`BucketOwnerEnforced`), and a bucket policy that denies any non-HTTPS
  request. `DeletionPolicy: Retain` means deleting the stack never deletes
  the bucket or its photos.
- An IAM **uploader user** with **no console access and no permissions
  beyond `s3:PutObject`/`s3:AbortMultipartUpload` on that one bucket**
  (optionally restricted further to a key prefix). The app only ever
  uploads and deletes local files on success — it never lists, reads, or
  deletes objects in S3 — so the policy doesn't grant those actions either.
- A second IAM **read-only user** (`wedding-photobooth-readonly` by default)
  scoped to `s3:ListBucket`/`s3:GetObject` only — no write, no delete. It's
  a separate user (not the uploader's) so read access can be handed out and
  revoked independently of the app's own upload credentials.

## Why the stack doesn't create either access key itself

CloudFormation *can* create an `AWS::IAM::AccessKey` resource and return the
secret as a stack output, but that secret then lives in plaintext in the
stack's change history and is retrievable by anyone with
`cloudformation:DescribeStacks` on the stack — `NoEcho` only masks it in the
console UI, it does not encrypt it. For a real AWS secret key, it's safer to
create the key with a one-off CLI call after the stack exists, which is not
persisted anywhere by CloudFormation.

## Deploy

```bash
aws cloudformation deploy \
  --template-file deploy/s3-photobooth-stack.yaml \
  --stack-name wedding-photobooth \
  --capabilities CAPABILITY_NAMED_IAM
```

(`CAPABILITY_NAMED_IAM` is required because the template creates named IAM
resources.) To use a different bucket name, either username, or restrict
uploads/reads to a key prefix, pass `--parameter-overrides`, e.g.:

```bash
aws cloudformation deploy \
  --template-file deploy/s3-photobooth-stack.yaml \
  --stack-name wedding-photobooth \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides ObjectKeyPrefix=photos
```

If you set `ObjectKeyPrefix`, enter the same value in the app's "S3 Key
Prefix" admin field — it also scopes what the read-only user can list/get.

## Create the access keys

Once the stack is up, create a key for whichever user you need:

```bash
aws iam create-access-key --user-name wedding-photobooth-uploader
aws iam create-access-key --user-name wedding-photobooth-readonly
```

Each prints an `AccessKeyId` and `SecretAccessKey` exactly once. The
uploader's goes into the app's admin settings screen (long-press the idle
screen → gear icon → PIN → settings). There's nowhere else to retrieve
either secret from afterward; if one is lost, delete the old key
(`aws iam delete-access-key`) and create a new one.

Check the stack outputs for the exact bucket name, region, and IAM usernames
to use:

```bash
aws cloudformation describe-stacks --stack-name wedding-photobooth --query "Stacks[0].Outputs"
```

## Browse/download photos read-only

With the read-only user's access key configured as a named AWS CLI profile
(or exported as `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`):

```bash
aws s3 ls s3://ryanpudd-weddingphotos/
aws s3 cp s3://ryanpudd-weddingphotos/IMG_20260912_193045_001.jpg .
```

## Tear down

The bucket and its contents are retained even if the stack is deleted
(`DeletionPolicy: Retain`), so `aws cloudformation delete-stack` is safe to
run without risking the photos — it removes the IAM users/policies only.
Empty and delete the bucket manually afterward if it's no longer needed:

```bash
aws s3 rm s3://ryanpudd-weddingphotos --recursive
aws s3api delete-bucket --bucket ryanpudd-weddingphotos
```
