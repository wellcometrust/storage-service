# TODO: this should become the main VHS once the migration to Azure is done
module "vhs_manifests_backfill" {
  source = "git::github.com/wellcomecollection/terraform-aws-vhs.git//hash-range-store?ref=v3.3.1"
  name   = "${var.namespace}-manifests"

  tags = var.tags

  # These prefixes exist for compatibility with older versions of the VHS
  # Terraform module.  Renaming S3 buckets or DynamoDB tables is hard, so
  # we preserve the existing names rather than change them.
  bucket_name_prefix = "wellcomecollection-vhs-backfill-"
  table_name_prefix  = "vhs-backfill"

  table_name = var.backfill_table_name
}