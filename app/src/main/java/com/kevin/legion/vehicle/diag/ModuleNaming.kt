package com.kevin.legion.vehicle.diag

/**
 * Names a module that answered and decides whether it is a restraint (airbag) module. Restraint is
 * flagged on ANY signal: the make's table says so, the module's own system name says so, or it
 * reports a SAE restraint-range code (B0000-B00FF, the supplemental-restraint block of J2012).
 */
object ModuleNaming {
    private val RESTRAINT_WORDS = listOf("RCM", "SRS", "AIRBAG", "AIR BAG", "RESTRAINT", "OCS", "ORC", "SDM", "ACU")
    private val RESTRAINT_CODE = Regex("^B00[0-9A-F]{2}$")
    private const val OBD_FIRST = 0x7E0L
    private const val OBD_LAST = 0x7E7L
    private const val HEX = 16
    private const val ID_11_DIGITS = 3

    fun report(c: Candidate, codes: CodeRead, ids: List<IdRead>, known: KnownModule?, make: String?): ModuleReport {
        val systemName = ids.firstOrNull { it.did == "F197" && it.outcome == Outcome.READ }?.value
        val (name, source) = when {
            known != null -> known.name to (ModuleCandidates.sourceFor(make) ?: "make table")
            !systemName.isNullOrBlank() -> systemName to "the module's own system name (DID F197)"
            c.requestId in OBD_FIRST..OBD_LAST -> "Emissions ECU ${c.address}" to ModuleCandidates.SOURCE_STANDARD
            else -> null to null
        }
        return ModuleReport(
            address = c.address,
            responseAddress = Hex.id(c.responseId),
            name = name,
            nameSource = source,
            restraint = isRestraint(known, systemName, codes),
            codes = codes,
            ids = ids,
        )
    }

    fun isRestraint(known: KnownModule?, systemName: String?, codes: CodeRead): Boolean =
        known?.restraint == true ||
            namesRestraint(systemName) ||
            codes.dtcs.any { RESTRAINT_CODE.matches(it.code) }

    private fun namesRestraint(systemName: String?): Boolean {
        val n = systemName?.uppercase() ?: return false
        return RESTRAINT_WORDS.any { w -> Regex("\\b$w\\b").containsMatchIn(n) }
    }

    /** True for the request ids ISO 15765-4 reserves for emissions-related ECUs. */
    fun isEmissionsAddress(address: String): Boolean =
        address.length == ID_11_DIGITS && address.toLongOrNull(HEX)?.let { it in OBD_FIRST..OBD_LAST } == true
}
