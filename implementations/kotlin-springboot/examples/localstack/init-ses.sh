#!/bin/sh
set -e
awslocal ses verify-email-identity --email-address no-reply@backend-service-playbook.example.com
