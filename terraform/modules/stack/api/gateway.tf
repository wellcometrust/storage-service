# API

resource "aws_api_gateway_rest_api" "api" {
  name = "Storage API (${var.namespace})"

  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

resource "aws_api_gateway_resource" "resource" {
  rest_api_id = aws_api_gateway_rest_api.api.id
  parent_id   = aws_api_gateway_rest_api.api.root_resource_id
  path_part   = "context.json"
}

module "root_resource_method_static" {
  source = "git::https://github.com/wellcometrust/terraform.git//api_gateway/prebuilt/method/static?ref=d817cd6"

  api_id      = aws_api_gateway_rest_api.api.id
  resource_id = aws_api_gateway_resource.resource.id

  aws_region  = var.aws_region
  bucket_name = aws_s3_bucket_object.context.bucket
  s3_key      = aws_s3_bucket_object.context.key

  static_resource_role_arn = aws_iam_role.static_resource_role.arn
}

resource "aws_api_gateway_authorizer" "cognito" {
  name          = "cognito"
  type          = "COGNITO_USER_POOLS"
  rest_api_id   = aws_api_gateway_rest_api.api.id
  provider_arns = [var.cognito_user_pool_arn]
}

# Domains

module "domain" {
  source = "./domain"

  domain_name      = var.domain_name
  cert_domain_name = var.cert_domain_name
}

# Stages

module "v1" {
  source = "git::https://github.com/wellcometrust/terraform.git//api_gateway/modules/stage?ref=4e68905"

  stage_name = "v1"

  api_id = aws_api_gateway_rest_api.api.id

  variables = {
    bags_port    = local.bags_listener_port
    ingests_port = local.ingests_listener_port
  }

  # All integrations
  dependencies = flatten([
    module.root_resource_method_static.integration_id,
    concat(
      module.bags.integration_uris,
      module.ingests.integration_uris,
    ),
  ])
}

# Resources

module "bags" {
  source = "../../resource"

  api_id    = aws_api_gateway_rest_api.api.id
  path_part = "bags"

  root_resource_id = aws_api_gateway_rest_api.api.root_resource_id
  connection_id    = aws_api_gateway_vpc_link.link.id

  cognito_id  = aws_api_gateway_authorizer.cognito.id
  auth_scopes = var.auth_scopes

  forward_port = "$${stageVariables.bags_port}"
  forward_path = "bags"
}

module "ingests" {
  source = "../../resource"

  api_id    = aws_api_gateway_rest_api.api.id
  path_part = "ingests"

  root_resource_id = aws_api_gateway_rest_api.api.root_resource_id
  connection_id    = aws_api_gateway_vpc_link.link.id

  cognito_id  = aws_api_gateway_authorizer.cognito.id
  auth_scopes = var.auth_scopes

  forward_port = "$${stageVariables.ingests_port}"
  forward_path = "ingests"
}

# Link

resource "aws_api_gateway_vpc_link" "link" {
  name        = "${var.namespace}-api_vpc_link"
  target_arns = [aws_lb.nlb.arn]
}

