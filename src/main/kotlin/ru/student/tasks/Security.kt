package ru.student.tasks

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITERATIONS = 600_000
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    suspend fun hash(password: String): String = withContext(Dispatchers.Default) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        listOf("pbkdf2_sha256", ITERATIONS.toString(), encoder.encodeToString(salt),
            encoder.encodeToString(derive(password, salt, ITERATIONS))).joinToString("$")
    }

    suspend fun verify(password: String, stored: String): Boolean = withContext(Dispatchers.Default) {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return@withContext false
        MessageDigest.isEqual(decoder.decode(parts[3]), derive(password, decoder.decode(parts[2]), parts[1].toInt()))
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}

class Tokens(private val settings: Settings) {
    val algorithm: Algorithm = Algorithm.HMAC256(settings.jwtSecret)
    val verifier = JWT.require(algorithm).withIssuer(settings.issuer).withAudience(settings.audience).build()

    fun issue(user: StoredUser): TokenResponse = TokenResponse(
        JWT.create().withSubject(user.id.toString()).withIssuer(settings.issuer).withAudience(settings.audience)
            .withIssuedAt(Date.from(Instant.now())).withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
            .sign(algorithm)
    )
}
