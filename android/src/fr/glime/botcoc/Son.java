package fr.glime.botcoc;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

/** Son du jeu coupé pendant que le bot joue (volume « média » muet), remis dès qu'il s'arrête,
 *  se met en pause ou que le jeu est caché. Un volume que tu avais déjà coupé n'est jamais remis.
 *  La marque dans les réglages sert après un plantage : le service remet le son en revenant. */
final class Son {
    private Son() {}

    private static boolean coupe = false;

    static synchronized void couper(Context c, boolean oui) {
        if (oui == coupe) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        SharedPreferences p = Reglages.prefs(c);
        try {
            if (oui) {
                if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                p.edit().putBoolean("sonCoupe", true).apply();
            } else {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                p.edit().remove("sonCoupe").apply();
            }
            coupe = oui;
        } catch (RuntimeException e) {
            Journal.log("Son : " + e.getMessage());
        }
    }

    /** Au démarrage du service : le son était resté coupé (plantage, service retiré). */
    static synchronized void reprendre(Context c) {
        if (!Reglages.prefs(c).getBoolean("sonCoupe", false)) return;
        coupe = true;
        couper(c, false);
    }
}
