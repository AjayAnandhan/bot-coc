package fr.glime.botcoc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Après une mise à jour du jeu, un bouton redessiné reste presque toujours à la même place.
 *  Quand plus rien n'est reconnu, le bot regarde ce qu'il y a à la place habituelle de ses
 *  boutons : si ça ressemble encore à moitié à l'un d'eux, il le touche, et si l'écran que ce
 *  bouton ouvre d'habitude arrive, il refait son image tout seul. Comme reparer() sur le PC. */
final class Reparation {
    private Reparation() {}

    static final double PLANCHER = 0.5;
    static double apres = 10.0;      // s sans rien reconnaître avant d'essayer

    private static final Map<String, String[]> SUITES = new HashMap<>();
    // boutons qu'on ne touche pas pour les vérifier : refaits quand leur voisin habituel est là
    private static final Map<String, String> TEMOINS = new HashMap<>();

    static {
        SUITES.put("combat", new String[]{"trouver_partie", "attaquer", "terminer_bataille", "recherche_rentrer"});
        SUITES.put("trouver_partie", new String[]{"attaquer", "terminer_bataille", "recherche_rentrer"});
        SUITES.put("attaquer", new String[]{"terminer_bataille", "recherche_rentrer"});
        SUITES.put("rentrer_village", new String[]{"combat"});
        SUITES.put("nuit/combat", new String[]{"nuit/trouver"});
        SUITES.put("nuit/trouver", new String[]{"barre"});
        SUITES.put("nuit/rentrer", new String[]{"nuit/combat"});
        TEMOINS.put("terminer_bataille", "suivant");
        TEMOINS.put("suivant", "terminer_bataille");
    }

    private static final Map<String, Integer> essais = new HashMap<>();
    static final List<String> reappris = new ArrayList<>();

    private static File fichier() { return new File(Config.templates(), "reparations.json"); }

    private static JSONObject lire() {
        try {
            return new JSONObject(new String(Files.readAllBytes(fichier().toPath()), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void ecrire(JSONObject d) {
        try {
            Files.write(fichier().toPath(), d.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
        }
    }

    /** Version du jeu à la dernière attaque réussie ("" : jamais notée). */
    static String versionVerifiee() { return lire().optString("jeu", ""); }

    static void noterVersion(String version) {
        JSONObject d = lire();
        try {
            d.put("jeu", version);
        } catch (JSONException ignored) {
        }
        ecrire(d);
    }

    private static boolean fige(Ecran avant, Ecran apres, int[] boite) {
        int x = (int) (boite[0] * Config.REDUCTION), y = (int) (boite[1] * Config.REDUCTION);
        int w = (int) (boite[2] * Config.REDUCTION), h = (int) (boite[3] * Config.REDUCTION);
        Img a = avant.gris().crop(x, y, x + w, y + h), b = apres.gris().crop(x, y, x + w, y + h);
        if (a.vide() || a.w != b.w || a.h != b.h) return false;
        long ecart = 0;
        for (int i = 0; i < a.p.length; i++) ecart += Math.abs((a.p[i] & 0xff) - (b.p[i] & 0xff));
        return (double) ecart / a.p.length < 6;
    }

    /** Refait l'image d'un bouton d'après l'écran ; l'ancienne est gardée dans templates/anciens/. */
    private static void apprendre(Trouve c, Ecran ecran, String preuve) {
        File f = new File(Config.templates(), c.nom + ".png");
        File ancien = new File(new File(Config.templates(), "anciens"), c.nom + ".png");
        try {
            ancien.getParentFile().mkdirs();
            Files.copy(f.toPath(), ancien.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
        int[] b = c.boite;
        if (!ecran.zone(b[0], b[1], b[0] + b[2], b[1] + b[3]).ecrire(f)) return;
        Gabarits.oublier(c.nom);
        essais.remove(c.nom);
        reappris.add(c.nom);
        JSONObject d = lire();
        try {
            JSONObject boutons = d.optJSONObject("boutons");
            if (boutons == null) {
                boutons = new JSONObject();
                d.put("boutons", boutons);
            }
            JSONObject v = new JSONObject();
            v.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE).format(new Date()));
            v.put("ressemblance", Math.round(c.score * 100) / 100.0);
            v.put("preuve", preuve);
            boutons.put(c.nom, v);
        } catch (JSONException ignored) {
        }
        ecrire(d);
        Journal.log("  bouton « " + c.nom + " » réappris tout seul : il ne ressemblait plus qu'à "
                + Math.round(c.score * 100) + " % à son image (" + preuve + ")");
    }

    /** Cherche parmi `noms` un bouton qui a changé d'allure sans changer de place et refait son
     *  image, une fois sûr que c'est bien lui : l'écran qu'il ouvre arrive après l'avoir touché
     *  (SUITES), ou son voisin habituel est à l'écran (TEMOINS). Ne touche rien tant qu'un bouton
     *  connu est affiché, ni si `toucher` est faux. Renvoie le nom du bouton réappris, sinon null. */
    static String reparer(Tel tel, List<String> noms, boolean toucher) {
        List<String> connus = new ArrayList<>();
        if (toucher) {
            for (String n : Combat.BOUTONS_CONNUS) if (Gabarits.existe(n)) connus.add(n);
            connus.addAll(Gabarits.popups());
        } else {
            for (String n : noms) if (TEMOINS.containsKey(n)) connus.add(TEMOINS.get(n));
            if (connus.isEmpty()) return null;
        }
        Ecran ecran;
        try {
            ecran = tel.capture();
        } catch (Bloque b) {
            return null;
        }
        List<String> presents = new ArrayList<>();
        for (String n : connus) {
            Gabarits.partout(n);
            if (Gabarits.localiser(ecran, n) != null) presents.add(n);
        }
        if (!toucher && presents.isEmpty()) return null;
        Trouve c = null;
        for (String nom : noms) {
            if (presents.contains(nom) || essais.getOrDefault(nom, 0) >= 3) continue;
            Trouve a = Gabarits.abime(ecran, nom, PLANCHER);
            if (a == null) continue;
            String temoin = TEMOINS.get(nom);
            if (temoin != null && presents.contains(temoin)) {
                tel.dormir(0.5);
                if (fige(ecran, tel.capture(), a.boite)) {
                    apprendre(a, ecran, "« " + temoin + " » est bien à l'écran");
                    return nom;
                }
            } else if (toucher && SUITES.containsKey(nom) && presents.isEmpty()) {
                if (c == null || a.score > c.score) c = a;
            }
        }
        if (c == null) return null;
        tel.dormir(0.5);
        if (!fige(ecran, tel.capture(), c.boite)) return null;     // une animation, pas un bouton
        essais.merge(c.nom, 1, Integer::sum);
        Journal.log("  rien de connu à l'écran, mais ce qui est à la place de « " + c.nom + " » lui ressemble à "
                + Math.round(c.score * 100) + " % : je le touche pour voir");
        tel.toucher(c.point());
        double debut = Tel.maintenant();
        while (Tel.maintenant() - debut < 25) {
            tel.dormir(0.4);
            Ecran e;
            try {
                e = tel.capture();
            } catch (Bloque b) {
                return null;
            }
            Gabarits.partout(c.nom);
            if (Gabarits.localiser(e, c.nom) != null) return null;  // reconnu maintenant : il était juste caché
            for (String n : SUITES.get(c.nom)) {
                boolean la = n.equals("barre") ? Nuit.lireBarre(e).valide() : Gabarits.localiser(e, n) != null;
                if (la) {
                    apprendre(c, ecran, "le toucher a bien ouvert « " + n + " »");
                    return c.nom;
                }
            }
            if (Tel.maintenant() - debut > 6 && fige(ecran, e, c.boite)) break;   // le toucher n'a rien fait
        }
        Journal.log("  l'écran attendu n'est pas venu : je garde l'ancienne image de « " + c.nom + " »");
        return null;
    }
}
