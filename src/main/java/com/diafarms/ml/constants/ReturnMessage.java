package com.diafarms.ml.constants;
public class ReturnMessage {

    private ReturnMessage() {}

    public static final String SUCCESS_REQUEST = "Opération réussie";
    public static final String SUCCESS_DELETE = "Suppression reussie";
    public static final String SUCCESS_ARCHIVED = "Archivage reussie";
    public static final String RESILIATION = "Résiliation reussie";
    public static final String SUCCESS_RESTORE= "Restoration reussie";
    public static final String SUCCESS_UNARCHIVE = "Désarchivage reussie";
    public static final String SUCCESS_REQUEST_SELECT_ROLE = "Recuperation des rôles réussie";
    public static final String SUCCESS_SEARCH_USER_REQUEST = "Rechercher des utilisateurs réussie";

    public static String accessNotFound(String uniqueId) {
        return "L'accès avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String adminNotFound(String uniqueId) {
        return "L'admin avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String addressNotFound(String uniqueId) {
        return "L'adresse avec l'uniqueId "+uniqueId+" est introuvable";
    }

    public static String batimentNotFound(String uniqueId) {
        return "Le batiment avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String chargeFixeNotFound(String uniqueId) {
        return "La chargeFixe avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String locatairePartitionNotFound(String uniqueId) {
        return "Le locatairePartition avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String logsNotFound(String uniqueId) {
        return "Le log avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String locataireNotFound(String uniqueId) {
        return "Le locataire avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String paiementLocationNotFound(String uniqueId) {
        return "Le paiementLocation avec l'uniqueId "+uniqueId+" est introuvable";
    }  

    public static String paiementProproprietaireNotFound(String uniqueId) {
        return "Le paiement [ "+uniqueId+" ] est introuvable";
    }
    public static String partitionNotFound(String uniqueId) {
        return "La partition avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String proprietaireNotFound(String uniqueId) {
        return "Le proprietaire avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String roleNotFound(String uniqueId) {
        return "Le role avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String salaireEmployeNotFound(String uniqueId) {
        return "Le salaireEmploye avec l'uniqueId "+uniqueId+" est introuvable";
    }
    public static String userNotFound(String uniqueId) {
        return "L'utilisateur avec l'uniqueId "+uniqueId+" est introuvable";
    }

    public static String userNotAuthorizeForUpdateChargeFixe(String userUniqueId, String uniqueId) {
        return "L'utilisateur avec l'uniqueId "+userUniqueId+
                " n'est pas autoriser à modifier la chargeFixe avec l'uniqueId "+uniqueId;
    }
    public static String userNotAuthorizeForUpdateBatiment(String userUniqueId, String uniqueId) {
        return "L'utilisateur avec l'uniqueId "+userUniqueId+
                " n'est pas autoriser à modifier le batiment avec l'uniqueId "+uniqueId;
    }
    public static String userNotAuthorizeForUpdateLocataire(String userUniqueId, String uniqueId) {
        return "L'utilisateur avec l'uniqueId "+userUniqueId+
                " n'est pas autoriser à modifier le locatiare avec l'uniqueId "+uniqueId;
    }
    public static String userNotAuthorizeForUpdateLocatairePartition(String locUniqueId, String partUniqueId) {
        return "Cet utilisateur n'est pas autoriser à modifier le locatiarePartion avec comme  uniqueId locataire"+locUniqueId+" et comme uniqueId partion "+partUniqueId;
    }
    public static String userNotAuthorizeForUpdatePaiementLocation(String userUniqueId, String uniqueId) {
        return "L'utilisateur avec l'uniqueId "+userUniqueId+
                " n'est pas autoriser à modifier le paiementLocation avec l'uniqueId "+uniqueId;
    }
    public static String userNotAuthorizeForUpdatePartition(String userUniqueId, String uniqueId) {
        return "L'utilisateur avec l'uniqueId "+userUniqueId+
                " n'est pas autoriser à modifier la partition avec l'uniqueId "+uniqueId;
    }
    public static String NoPartition(String designation) {
        return "Aucune partition avec la designation "+designation;
    }
    public static String userNotAuthorizeForUpdateProprietaire(String nom) {
        return "Le propriétaire avec " +nom + " n'existe";
    }

    public static String NUMBER_ALREADY_EXISTS_FOR_PROPRIETAIRE(String numero) {       
        return "Le numero "+numero+" est deja attribuer";    
    }

    public static String EMAIL_ALREADY_EXISTS_FOR_PROPRIETAIRE(String numero) {       
        return "L'email "+numero+" est deja attribuer";    
    }

    public static String encaisseurNotFound(String uniqueId) {
        return "L'encaisseur d'uniqueId "+uniqueId+" est introuvable";
    }

    public static String adminNotAuthorizeForUpdateEncaisseur(String uniqueIdAdmin, String uniqueId) {
        return "L'admin d'uniqueId "+uniqueIdAdmin+" n'est pas autoriser à modifier l'encaisseur d'uniqueId "+uniqueId;
    }

    public static String partitionAlreadyExists(String nom) {
        return "La partition avec le nom "+nom+" existe deja";
    }

    public static String batimentInexistante(String unique_id) {
        return "Le batiment avec unique id "+unique_id+" n'existe pas";
    }
     public static String travauxInexistante(String unique_id) {
        return "Le travaux avec unique id "+unique_id+" n'existe pas";
    }
}
