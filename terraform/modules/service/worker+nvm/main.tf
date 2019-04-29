module "service" {
  source = "github.com/wellcometrust/terraform-modules.git//ecs/prebuilt/scaling+nvm?ref=4b3e465"

  service_name    = "${var.service_name}"
  container_image = "${var.container_image}"

  subnets = "${var.subnets}"

  namespace_id = "${var.namespace_id}"

  cluster_id   = "${var.cluster_id}"
  cluster_name = "${var.cluster_name}"

  service_egress_security_group_id = "${var.service_egress_security_group_id}"

  cpu    = "${var.cpu}"
  memory = "${var.memory}"

  security_group_ids = ["${var.security_group_ids}"]

  metric_namespace = "${var.metric_namespace}"
  high_metric_name = "${var.high_metric_name}"

  env_vars        = "${var.env_vars}"
  env_vars_length = "${var.env_vars_length}"

  secret_env_vars        = "${var.secret_env_vars}"
  secret_env_vars_length = "${var.secret_env_vars_length}"

  desired_task_count = "${var.desired_task_count}"

  min_capacity = "${var.min_capacity}"
  max_capacity = "${var.max_capacity}"
}
