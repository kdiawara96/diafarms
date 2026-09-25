package com.diafarms.ml.enums;

// Journal des actions faites côté serveur (web) sur une session de pesée. La synchro
// mobile n'écrit jamais d'événement.
public enum TypeEvenementPesee {
    CREATION_WEB, AJOUT_WEB, MODIFICATION_WEB, ANNULATION_WEB, TERMINAISON_WEB
}
