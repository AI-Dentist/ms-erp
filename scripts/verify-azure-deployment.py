#!/usr/bin/env python3
"""Offline structural checks; does not read the server environment or contact Docker."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent


class AzureDeployment(unittest.TestCase):
    def text(self, name):
        path = ROOT / name
        self.assertTrue(path.is_file(), f"missing {name}")
        return path.read_text(encoding="utf-8")

    def test_image_and_ignore(self):
        docker = self.text("Dockerfile")
        for fragment in ("maven:", "eclipse-temurin:25", " AS build", "USER ", "EXPOSE 8080"):
            self.assertIn(fragment, docker)
        self.assertNotIn("ARG ", docker)
        ignore = self.text(".dockerignore")
        for fragment in (".env*", "*.pem", "*.key", ".git", "target", "node_modules"):
            self.assertIn(fragment, ignore)

    def test_compose(self):
        compose = self.text("deploy/azure/compose.yml")
        for fragment in ("name: dental-erp", "postgres-erp:", "image: postgres:18", "/var/lib/postgresql", "ms1-erp:", "../../.env", "SPRING_PROFILES_ACTIVE: prod", "ERP_PORT: 8080", "external: true", "unless-stopped", "healthcheck:"):
            self.assertIn(fragment, compose)
        self.assertNotIn("ports:", compose)
        self.assertEqual(compose.count("restart: unless-stopped"), 2)

    def test_environment(self):
        env = self.text("deploy/azure/env.template")
        for line in ("COMPOSE_FILE=deploy/azure/compose.yml", "ERP_DB_URL=jdbc:postgresql://postgres-erp:5432/", "ERP_DB_USER=", "ERP_DB_PASSWORD=", "KEYCLOAK_ISSUER_URI=", "PAGOFACIL_MOCK_ENABLED=true", "AI_PROVIDER=openai", "OPENAI_API_KEY=PEGAR_AQUI_TU_OPENAI_API_KEY", "OPENAI_MODEL=gpt-4o-mini", "GEMINI_API_KEY=PEGAR_AQUI_TU_GEMINI_API_KEY", "GEMINI_MODEL=gemini-2.5-flash", "N8N_WEBHOOK_URL=http://localhost:5678/webhook/erp-notificaciones", "AWS_DEFAULT_REGION=us-east-1", "https://20-83-242-208.sslip.io", "https://100-60-248-245.sslip.io"):
            self.assertIn(line, env)

    def test_workflow_and_docs(self):
        workflow = self.text(".github/workflows/deploy-azure.yml")
        for fragment in ("main", "master", "workflow_dispatch:", "contents: read", "concurrency:", "cancel-in-progress: false", "actions/checkout@v4", "secrets.AZURE_SSH_KEY", "chmod 600", "ssh-keyscan", "20.83.242.208", "rsync -az --delete --exclude .git --exclude .env", "azureuser@20.83.242.208:/home/azureuser/deploy/ms-erp/", "cd /home/azureuser/deploy/ms-erp && docker compose up -d --build --remove-orphans"):
            self.assertIn(fragment, workflow)
        self.assertNotIn("git pull", workflow)
        self.assertNotIn("appleboy/", workflow)
        docs = self.text("docs/azure-deployment.md")
        for fragment in ("/home/azureuser/deploy/ms-erp/.env", "RabbitMQ", "S3", "report", "dental-net"):
            self.assertIn(fragment, docs)


if __name__ == "__main__":
    unittest.main()
