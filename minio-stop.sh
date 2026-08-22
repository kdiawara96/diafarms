#!/bin/bash

PID=$(pgrep -f "$HOME/minio server $HOME/minio-data")

if [ -n "$PID" ]; then
    echo "🛑 Arrêt de MinIO..."
    kill $PID
    echo "✅ MinIO arrêté."
else
    echo "ℹ️ MinIO n'est pas en cours d'exécution."
fi
