package fr.glime.botcoc;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.CRC32;

/** Copie les modèles du bot (templates/ du PC, dans l'appli) vers les fichiers de l'appli, sans
 *  écraser ce que le bot a appris sur le téléphone (bords de la carte, place des boutons). À
 *  chaque nouvelle version de l'appli, les modèles que l'appli apporte changés sont remplacés :
 *  sinon un bouton refait sur le PC n'arriverait jamais sur le téléphone. Un modèle que l'appli
 *  n'a pas changé reste tel qu'il est sur le téléphone, même si le bot l'a réappris tout seul. */
final class Fichiers {
    private Fichiers() {}

    private static final List<String> APPRIS = Arrays.asList("boutons.json", "carte.json");

    static synchronized void installer(Context c) {
        Config.racine = c.getExternalFilesDir(null);
        AssetManager am = c.getAssets();
        File marque = new File(Config.templates(), ".version");
        File suivi = new File(Config.templates(), ".installes");
        String version = version(c);
        boolean nouvelle = !version.equals(lire(marque));
        Properties installes = new Properties();        // empreinte de chaque modèle de l'appli déjà copié
        try (InputStream in = new FileInputStream(suivi)) {
            installes.load(in);
        } catch (IOException ignored) {
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(am.open("liste.txt"), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.startsWith("./")) l = l.substring(2);
                if (l.isEmpty()) continue;
                File f = new File(Config.templates(), l);
                if (f.exists() && (!nouvelle || APPRIS.contains(l))) continue;
                byte[] contenu = asset(am, "templates/" + l);
                CRC32 crc = new CRC32();
                crc.update(contenu);
                String empreinte = contenu.length + "-" + crc.getValue();
                boolean inchange = empreinte.equals(installes.getProperty(l));
                installes.setProperty(l, empreinte);
                if (f.exists() && (inchange || Arrays.equals(contenu, Files.readAllBytes(f.toPath())))) continue;
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), contenu);
            }
            if (nouvelle) {
                Files.write(marque.toPath(), version.getBytes(StandardCharsets.UTF_8));
                try (OutputStream out = new FileOutputStream(suivi)) {
                    installes.store(out, null);
                }
            }
        } catch (IOException e) {
            Journal.log("Modèles du bot illisibles : " + e.getMessage());
        }
        Config.debugDossier().mkdirs();
    }

    private static String version(Context c) {
        try {
            return String.valueOf(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static String lire(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] asset(AssetManager am, String nom) throws IOException {
        try (InputStream in = am.open(nom)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
