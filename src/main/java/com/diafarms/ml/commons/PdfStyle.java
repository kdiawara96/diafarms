package com.diafarms.ml.commons;

import java.awt.Color;
import java.util.List;

import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;

// Styles/couleurs/cellules partagés par les PDF générés côté back (facture,
// bulletin de paie — voir FactureServiceImpl/SalaireServiceImpl) pour qu'ils aient
// une identité visuelle cohérente entre eux plutôt que du texte brut sans mise en
// forme. Couleur PRIMARY = vert Diafarms (hsl(152,45%,28%) côté web, voir
// Diafarms_web/src/index.css --primary), pas une couleur choisie au hasard.
public final class PdfStyle {

    public static final Color PRIMARY = new Color(39, 104, 74);
    public static final Color PRIMARY_LIGHT = new Color(232, 242, 237);
    public static final Color BORDER = new Color(224, 224, 224);
    public static final Color MUTED = new Color(120, 120, 120);
    public static final Color TEXT = new Color(45, 45, 45);
    public static final Color WHITE = Color.WHITE;

    public static Font title() { return new Font(Font.HELVETICA, 22, Font.BOLD, PRIMARY); }
    public static Font sectionLabel() { return new Font(Font.HELVETICA, 9, Font.BOLD, MUTED); }
    public static Font bigAmount() { return new Font(Font.HELVETICA, 20, Font.BOLD, WHITE); }
    public static Font bigAmountLabel() { return new Font(Font.HELVETICA, 10, Font.BOLD, WHITE); }
    public static Font bold() { return new Font(Font.HELVETICA, 10, Font.BOLD, TEXT); }
    public static Font normal() { return new Font(Font.HELVETICA, 10, Font.NORMAL, TEXT); }
    public static Font small() { return new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED); }
    public static Font tableHeader() { return new Font(Font.HELVETICA, 10, Font.BOLD, WHITE); }
    public static Font badge() { return new Font(Font.HELVETICA, 10, Font.BOLD, WHITE); }

    /** Bandeau de couleur pleine largeur, sans contenu — sépare l'en-tête du reste
     * du document (mirroir visuel du bandeau utilisé sur le web, voir DashboardLayout). */
    public static PdfPTable colorBand(float heightPt) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(heightPt);
        cell.setBackgroundColor(PRIMARY);
        cell.setBorder(Rectangle.NO_BORDER);
        t.addCell(cell);
        return t;
    }

    public static PdfPCell tableHeaderCell(String text) {
        PdfPCell c = new PdfPCell(new Paragraph(text, tableHeader()));
        c.setBackgroundColor(PRIMARY);
        c.setPadding(8f);
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    public static PdfPCell bodyCell(String text) {
        return bodyCell(text, Element.ALIGN_LEFT);
    }

    public static PdfPCell bodyCell(String text, int alignment) {
        PdfPCell c = new PdfPCell(new Paragraph(text, normal()));
        c.setPadding(8f);
        c.setBorderColor(BORDER);
        c.setBorderWidth(0.75f);
        c.setHorizontalAlignment(alignment);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    /** Encart discret (fond vert très clair, sans bordure) pour un bloc d'infos —
     * client sur une facture, employé sur un bulletin de paie. */
    public static PdfPCell infoBox(List<Paragraph> lignes) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(PRIMARY_LIGHT);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(12f);
        for (Paragraph p : lignes) c.addElement(p);
        return c;
    }

    /** Étiquette colorée (fond plein, texte blanc) — statut d'une facture. */
    public static PdfPCell badgeCell(String texte, Color couleur) {
        PdfPCell c = new PdfPCell(new Paragraph(texte, badge()));
        c.setBackgroundColor(couleur);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(6f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    public static Color statutFactureColor(String statut) {
        if ("PAYEE".equals(statut)) return new Color(22, 130, 78);
        if ("PARTIELLE".equals(statut)) return new Color(202, 138, 4);
        return new Color(185, 45, 45); // IMPAYEE
    }

    /** Bloc plein-largeur, fond PRIMARY, texte blanc — met en avant LE montant qui
     * compte (reste dû sur une facture, net payé sur un bulletin). */
    public static PdfPTable highlightAmount(String libelle, String montant) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        Paragraph label = new Paragraph(libelle, bigAmountLabel());
        Paragraph value = new Paragraph(montant, bigAmount());
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(PRIMARY);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(14f);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.addElement(label);
        c.addElement(value);
        t.addCell(c);
        return t;
    }

    /** Bloc "qui envoie ce document" (nom de la ferme, adresse, contact) affiché à
     * gauche de l'en-tête facture/bulletin, à côté du logo — prend des chaînes plutôt
     * que l'entité Farm pour que ce module reste indépendant du package models. Une
     * ligne n'est ajoutée que si non vide, pour ne pas laisser de lignes fantômes tant
     * que la ferme n'a pas rempli ses coordonnées (voir Paramètres > Identité de la
     * ferme côté web).
     */
    public static java.util.List<Paragraph> farmBlockLines(String nom, String quartier, String ville, String pays, String telephone1, String telephone2, String email) {
        java.util.List<Paragraph> lignes = new java.util.ArrayList<>();
        if (nonBlank(nom)) lignes.add(new Paragraph(nom, bold()));
        String adresse = joinNonBlank(", ", quartier, ville, pays);
        if (nonBlank(adresse)) lignes.add(new Paragraph(adresse, small()));
        String telephones = joinNonBlank(" / ", telephone1, telephone2);
        if (nonBlank(telephones)) lignes.add(new Paragraph("Tél : " + telephones, small()));
        if (nonBlank(email)) lignes.add(new Paragraph(email, small()));
        return lignes;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String joinNonBlank(String separateur, String... valeurs) {
        StringBuilder sb = new StringBuilder();
        for (String v : valeurs) {
            if (nonBlank(v)) {
                if (sb.length() > 0) sb.append(separateur);
                sb.append(v);
            }
        }
        return sb.toString();
    }

    /** Cellule sans bordure, pour composer un tableau de mise en page (en-tête
     * logo/titre, pied de page) sans que ça ressemble à un tableau de données. */
    public static PdfPCell layoutCell(Element... elements) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0f);
        for (Element e : elements) c.addElement(e);
        return c;
    }

    private PdfStyle() {
    }
}
