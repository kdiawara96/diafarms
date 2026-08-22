#!/bin/bash

set -e

if [ -z "${DEPLOY_REEXECED:-}" ]; then
  echo "📥 Mise à jour du code"
  git pull
  # Le pull peut avoir modifié ce script : on se relance pour relire le fichier à jour
  # depuis le début plutôt que de continuer une lecture désynchronisée.
  export DEPLOY_REEXECED=1
  exec "$0" "$@"
fi

if [ ! -f .env ]; then
  echo "❌ .env introuvable. Lance : cp .env.prod.example .env, remplis-le, puis relance ce script."
  exit 1
fi

set -a
source .env
set +a

for var in POSTGRES_USER_PASSWORD MINIO_SECRET_KEY SECRET_KEY AES_SECRET_KEY ADMIN_PASSWORD; do
  if [ -z "${!var:-}" ]; then
    echo "❌ La variable $var est vide dans .env. Remplis-la avant de redéployer."
    exit 1
  fi
done

echo "🧹 Arrêt du service"
docker compose -f docker-compose.prod.yml down

echo "🏗 Build du backend"
./mvnw clean package -DskipTests

echo "🐳 Build Docker"
docker compose -f docker-compose.prod.yml build --no-cache

echo "🚀 Démarrage du service"
docker compose -f docker-compose.prod.yml up -d

docker ps
