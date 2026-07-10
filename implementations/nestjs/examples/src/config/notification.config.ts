const DEFAULT_SENDER_EMAIL = 'no-reply@backend-service-playbook.example.com'

export function getSesSenderEmail(): string {
  return process.env.SES_SENDER_EMAIL ?? DEFAULT_SENDER_EMAIL
}
