package com.diafarms.ml.services;

import com.diafarms.ml.DTO.PaiementSalaireDTO;
import com.diafarms.ml.DTO.SalaireDTO;
import com.diafarms.ml.DTO.TauxSalaireDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.SalaireDefinirRequest;
import com.diafarms.ml.request.others.SalairePayerRequest;

public interface SalaireService {
    // Upsert : crée le salaire de base de l'employé s'il n'existe pas, met à jour
    // mode/taux sinon (un seul Salaire par employé, voir Salaire.employe unique).
    // Historise le taux précédent dans SalaireHistorique s'il change réellement.
    SalaireDTO definir(SalaireDefinirRequest data);
    // Génère une vraie Transaction (SORTIE, "Salaires", SourceTransaction.SALAIRE) —
    // voir TransactionService.createSortieCommune — au plus un paiement par période.
    // Le montant par défaut (si non forcé explicitement) utilise le taux HISTORIQUE
    // de la période payée, pas le taux actuel de la grille (voir resolveTauxPourPeriode).
    PaiementSalaireDTO payer(SalairePayerRequest data);
    PaginatedResponse<SalaireDTO> list(int page, int size);
    PaginatedResponse<PaiementSalaireDTO> listPaiements(String employeUniqueId, int page, int size);
    // Bulletin de paie PDF pour un paiement précis — voir SalaireServiceImpl, mirroir
    // de FactureServiceImpl.genererPdf (logo/tampon de la ferme insérés si présents).
    byte[] genererBulletinPdf(String paiementUniqueId);
    // Taux réellement en vigueur pour UNE période donnée (peut différer du taux actuel
    // si la grille a changé depuis) — utilisé par le web pour pré-remplir "Payer le
    // salaire" avec le bon montant avant même de valider.
    TauxSalaireDTO getTauxPourPeriode(String employeUniqueId, String periode);
}
