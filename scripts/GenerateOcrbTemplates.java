// Sceau - génération des modèles de caractères OCR-B de :mrz (D32, lot B).
// Programme Java autonome, lancé par scripts/generate-ocrb-templates.sh (voir mrz/README.md).
//
// Pour chacune des 37 classes de la MRZ (A-Z, 0-9, <), calcule la couverture d'encre du glyphe
// de la police OCR-B dans une grille de TW x TH pixels. La grille couvre horizontalement la chasse
// du glyphe (police à chasse fixe) et verticalement la hauteur de référence REF_HEIGHT au-dessus
// de la ligne de base, avec une marge de MARGIN x REF_HEIGHT en haut et en bas. La couverture est
// estimée par SUB x SUB points d'échantillonnage par pixel, testés sur le contour exact du glyphe
// (Shape.contains) : aucun rendu anti-crénelé, le résultat ne dépend que de la géométrie.
//
// Format de la ressource (octets) :
//   "OCRB", version (1), TW, TH, nombre de classes N, puis les N caractères en ASCII,
//   puis N x TH x TW octets de couverture (0 = papier, 255 = encre), ligne par ligne.
import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

public class GenerateOcrbTemplates {
    static final String CLASSES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<";
    static final int TW = 16;
    static final int TH = 20;
    static final double UNITS = 1000.0;
    static final double REF_HEIGHT = 740.0;
    static final double MARGIN = 0.12;
    static final int SUB = 8;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage : GenerateOcrbTemplates POLICE.otf SORTIE.bin");
            System.exit(2);
        }
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(args[0])).deriveFont((float) UNITS);
        FontRenderContext frc = new FontRenderContext(null, false, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {'O', 'C', 'R', 'B', 1, TW, TH, (byte) CLASSES.length()});
        for (char c : CLASSES.toCharArray()) out.write(c);
        double top = -REF_HEIGHT * (1 + MARGIN);
        double bottom = REF_HEIGHT * MARGIN;
        for (char c : CLASSES.toCharArray()) {
            GlyphVector gv = font.createGlyphVector(frc, String.valueOf(c));
            double advance = gv.getGlyphMetrics(0).getAdvance();
            Shape shape = gv.getOutline();
            double cellW = advance / TW;
            double cellH = (bottom - top) / TH;
            for (int y = 0; y < TH; y++) {
                for (int x = 0; x < TW; x++) {
                    int inside = 0;
                    for (int sy = 0; sy < SUB; sy++) {
                        for (int sx = 0; sx < SUB; sx++) {
                            double px = (x + (sx + 0.5) / SUB) * cellW;
                            double py = top + (y + (sy + 0.5) / SUB) * cellH;
                            if (shape.contains(px, py)) inside++;
                        }
                    }
                    out.write((int) Math.round(inside * 255.0 / (SUB * SUB)));
                }
            }
        }
        byte[] bytes = out.toByteArray();
        Files.write(new File(args[1]).toPath(), bytes);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        System.out.println(bytes.length + " octets, SHA-256 " + hex);
    }
}
