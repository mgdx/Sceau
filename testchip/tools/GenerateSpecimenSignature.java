/*
 * Sceau - generates testchip/src/main/resources/specimen-signature.jpg, the handwritten signature
 * of the simulated CNIe (DG7, decision D37).
 *
 * The image is entirely synthetic: a flourish made of Bézier curves computed by this program,
 * drawn in black on a white background. No real signature is involved and no font is used
 * (identical rendering from one machine to another, up to the JPEG encoder).
 *
 * Usage (from the repository root, JDK 17 or later):
 *   java testchip/tools/GenerateSpecimenSignature.java
 */
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public class GenerateSpecimenSignature {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 100;
    private static final float QUALITY = 0.8f;
    private static final long MAX_BYTES = 8 * 1024;

    public static void main(String[] args) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Path2D.Float stroke = new Path2D.Float();
        stroke.moveTo(20, 70);
        stroke.curveTo(30, 10, 60, 10, 55, 55);
        stroke.curveTo(50, 85, 80, 80, 95, 45);
        stroke.curveTo(105, 25, 115, 75, 130, 50);
        stroke.curveTo(140, 35, 150, 70, 165, 48);
        stroke.curveTo(180, 25, 190, 75, 205, 50);
        stroke.curveTo(220, 30, 235, 65, 250, 45);
        stroke.curveTo(262, 30, 270, 40, 280, 35);
        g.draw(stroke);

        Path2D.Float underline = new Path2D.Float();
        underline.moveTo(40, 85);
        underline.curveTo(120, 78, 200, 92, 270, 80);
        g.draw(underline);
        g.dispose();

        File out = new File("testchip/src/main/resources/specimen-signature.jpg");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(QUALITY);
        out.delete();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        if (out.length() > MAX_BYTES) {
            throw new IllegalStateException("specimen-signature.jpg exceeds " + MAX_BYTES + " bytes");
        }
        System.out.println(out + ": " + out.length() + " bytes");
    }
}
