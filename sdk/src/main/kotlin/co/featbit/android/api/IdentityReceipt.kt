package co.featbit.android.api

/**
 * Evidence of identity adoption, not flag readiness or proof that the identity is still current.
 * [generation] is local to the issuing client, not a user identifier; never compare it across
 * clients. Later changes never alter a completed receipt.
 */
public class IdentityReceipt internal constructor(public val generation: Long)
