# Atlas infrastructure root module. Composes the platform's cloud dependencies:
# an EKS cluster, an RDS Postgres with automated snapshots + PITR, an MSK/Kafka
# cluster, ElastiCache Redis, and a Temporal Cloud namespace. State is remote
# (S3 + DynamoDB lock). Environment-specific values are supplied via tfvars.

terraform {
  required_version = ">= 1.9"
  backend "s3" {
    bucket         = "atlas-tf-state"
    key            = "platform/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "atlas-tf-lock"
    encrypt        = true
  }
}

module "database" {
  source                 = "./modules/rds-postgres"
  instance_class         = "db.r6g.large"
  multi_az               = true      # HA for the ledger
  backup_retention_days  = 35        # supports point-in-time restore
  deletion_protection    = true
  storage_encrypted      = true
}

module "kafka"    { source = "./modules/msk" }
module "cache"    { source = "./modules/elasticache-redis" }
module "cluster"  { source = "./modules/eks" }
