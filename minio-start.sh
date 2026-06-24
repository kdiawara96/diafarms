#!/bin/bash

# === CONFIG ===
MINIO_USER="diafarms_admin"
MINIO_PASS="Df_2026_Secure_MinIO_Key_2026"
MINIO_DIR="$HOME/minio-data"
MINIO_BIN="$HOME/minio"

# === COULEURS ===
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}🚀 Démarrage de MinIO...${NC}"
echo -e "${YELLOW}📁 Dossier données: $MINIO_DIR${NC}"

# Créer le dossier si inexistant
mkdir -p "$MINIO_DIR"

# Lancer MinIO
export MINIO_ROOT_USER=$MINIO_USER
export MINIO_ROOT_PASSWORD=$MINIO_PASS

$MINIO_BIN server "$MINIO_DIR" --console-address ":9001"