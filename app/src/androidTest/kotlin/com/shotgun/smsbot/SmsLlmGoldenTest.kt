package com.shotgun.smsbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shotgun.smsbot.util.SmsLlmInterpreter
import com.shotgun.smsbot.util.ModelDownloadManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Tests d'intégration Gemma — nécessitent le modèle téléchargé sur l'appareil.
 * Lancez-les via : ./gradlew connectedAndroidTest
 *
 * Si le modèle est absent, tous les tests sont ignorés (Assume).
 */
@RunWith(AndroidJUnit4::class)
class SmsLlmGoldenTest {

    private val ctx by lazy { InstrumentationRegistry.getInstrumentation().targetContext }

    @Before
    fun requireModel() {
        assumeTrue(
            "Modèle absent — téléchargez-le depuis l'app pour exécuter ces tests.",
            ModelDownloadManager.isModelReady(ctx)
        )
    }

    // ── Vrais SMS de disponibilité (should match) ─────────────────────────────

    @Test
    fun `couverture vol 02-06 — recherche OPL directe`() = runBlocking {
        val sms = """
            **COUVERTURE DES VOLS REGULATION PN**
            Bonjour,
            Nous sommes à la recherche de OPL pour opérer des vols 02/06/2026 :
            ORY HER ORY (32N) // Départ 11H55TU
            ORY TNG ORY (32N) // Départ 14H30TU
            Si disponible & pleinement reposé(e), vous pouvez contacter la Régulation PN
            par mail à Regul.PN@fr.transavia.com ou par téléphone au 0185163211, Touche 1.
            Merci pour votre disponibilité !
            REGULATION PN
            Kahina
        """.trimIndent()
        assertEquals("02/06", SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `suivi planning 09-06 — recherche OPL`() = runBlocking {
        val sms = """
            **Suivi planning**
            Bonjour,
            Nous sommes actuellement à la recherche OPL pour la journée du 09/06
            ORY AGP ORY 13H55 19H45 TU
            Si vous etes disponible n'hésitez pas à nous appeler.
            Cordialement
            Mariam
            Choix 3 au 0185163211 / +33185163211
        """.trimIndent()
        assertEquals("09/06", SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `date en format texte — quinze juin`() = runBlocking {
        val sms = "Bonjour, nous cherchons un OPL disponible le quinze juin pour un vol ORY-AGP. Merci de nous rappeler."
        assertEquals("15/06", SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `date avec tiret — 15-06`() = runBlocking {
        val sms = "Recherche OPL disponible pour le vol du 15-06. Contactez la régulation au 0185163211."
        assertEquals("15/06", SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `date avec point — 15 06`() = runBlocking {
        val sms = "Nous avons besoin d'un OPL pour le 15.06. Merci de vous manifester rapidement."
        assertEquals("15/06", SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `reference a demain avec date`() = runBlocking {
        val sms = "Bonjour, êtes-vous disponible demain le 10/06 pour un vol ORY-MAD ? Régulation PN."
        assertEquals("10/06", SmsLlmInterpreter.extractDate(ctx, sms))
    }

    // ── Faux positifs — ne doivent PAS déclencher d'appel ────────────────────

    @Test
    fun `modification planning — ne pas matcher`() = runBlocking {
        val sms = """
            Bonjour Guillaume, suite régulation, merci de noter modif vol demain 03/06,
            tu sera en OPL safety sur le lifus 2/2 d'un nouveau stagiaire OPL.
            Nous te remercions pour la bonne prise en compte.
            Bonne journée
            Caroline
        """.trimIndent()
        assertNull(SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `annulation vol — ne pas matcher`() = runBlocking {
        val sms = "Bonjour, votre vol du 20/06 ORY-BCN est annulé. Nous nous excusons pour la gêne occasionnée."
        assertNull(SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `information generale sans dispo — ne pas matcher`() = runBlocking {
        val sms = "Transavia vous informe d'une mise à jour de planning pour le mois de juin. Consultez votre espace crew."
        assertNull(SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `confirmation de prise en compte — ne pas matcher`() = runBlocking {
        val sms = "Bonjour, votre disponibilité pour le 15/06 a bien été enregistrée. Merci."
        assertNull(SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `rappel planning existant — ne pas matcher`() = runBlocking {
        val sms = "Rappel : vous avez un vol prévu le 22/06 ORY-LIS 08H30 TU. Bon vol !"
        assertNull(SmsLlmInterpreter.extractDate(ctx, sms))
    }

    @Test
    fun `SMS sans date ni dispo — ne pas matcher`() = runBlocking {
        val sms = "Bonjour, merci de consulter votre planning sur l'application crew. Cordialement, Régulation PN."
        assertNull(SmsLlmInterpreter.extractDate(ctx, sms))
    }
}
