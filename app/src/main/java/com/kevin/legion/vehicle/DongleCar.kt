package com.kevin.legion.vehicle

/**
 * Which car the one OBD dongle is in on this connection, and how that was decided (OBD VIN
 * attribution, Kevin 2026-11-15: one dongle moves between the F-150, the Outlander and maybe a
 * RAV4).
 *
 * Every OBD sample, drive, code event and capability scan of a connection is written under
 * [recordingId]. When the car is known that is the car's own id. When it is not, it is a
 * PENDING id ([pendingIdForVin] / [pendingIdForDongle]) that no `vehicles` row has, so nothing
 * is filed under a guess: the readings stay on the phone, the upload halts at them
 * (`ObdSampleReconcile`), and the answer re-keys them onto the car Kevin names.
 *
 * Pure: the decision ([decide]) and the answer ([planAnswer]) are JVM-tested without Android.
 */
sealed interface DongleCar {
    /** The dongle's Bluetooth address (or the emulator's host:port label). */
    val mac: String

    /** The id this connection's OBD data is written under. */
    val recordingId: String

    /** A car on file. [basis] says why it is this car. */
    data class OnCar(
        override val mac: String,
        val carId: String,
        /** The VIN read off this connection, when one was. */
        val vin: String?,
        val basis: Basis,
        /** The car picked by hand that this VIN overrode for live data, said once in words. */
        val pickOverridden: String? = null,
    ) : DongleCar {
        override val recordingId: String get() = carId
    }

    /** No car yet: the data is held under a pending id until Kevin says which car. */
    data class Held(
        override val mac: String,
        val vin: String?,
        val reason: HeldReason,
        /**
         * When this held session began, for a no-VIN hold: two unanswered no-VIN sessions (maybe
         * two different cars) must never pool under one id. 0 for a VIN-keyed hold, where the VIN
         * already says which car.
         */
        val since: Long = 0L,
    ) : DongleCar {
        override val recordingId: String
            get() = if (vin != null) VIN_PREFIX + vin.uppercase() else pendingIdForDongle(mac, since)
    }

    enum class Basis {
        /** The VIN read off the car matched exactly one car on file. A fact. */
        VIN,

        /** Kevin said which car it is, on this connection. */
        ANSWER,

        /** No VIN could be read; this car's learned fingerprint matched exactly ([CarFingerprint]). A default. */
        ASSUMED,
    }

    enum class HeldReason {
        /** A VIN was read and no car on file has it. */
        UNKNOWN_VIN,

        /** A VIN was read and more than one car on file has it: a data error, never a pick. */
        SHARED_VIN,

        /** No VIN could be read and no car's fingerprint matched this connect exactly. */
        NO_VIN,
    }

    /** One car on file, as the decision needs it: its id and its VIN ("" when none is on file). */
    data class CarVin(val carId: String, val vin: String)

    /** What answering "which car is it" will do. [planAnswer] builds it; the controller runs it. */
    sealed interface AnswerPlan {
        /** Already on that car: nothing moves. [settles] when it confirms an assumption. */
        data class Keep(val carId: String, val settles: Boolean) : AnswerPlan

        /**
         * Move this connection's data from [fromId] to [toCarId]. [fromId] is a pending id (data
         * never left the phone, so no correction rule is needed) or the assumed car (a rule is
         * recorded, [asCorrection]). [writeVin] is saved onto [toCarId]; [clearVinFrom] lists the
         * other cars that wrongly held the same VIN.
         */
        data class Move(
            val fromId: String,
            val toCarId: String,
            val asCorrection: Boolean,
            val writeVin: String?,
            val clearVinFrom: List<String> = emptyList(),
        ) : AnswerPlan

        /** [carId] already has a DIFFERENT VIN on file. Nothing changes until Kevin says replace. */
        data class VinConflict(val carId: String, val onFile: String, val read: String) : AnswerPlan

        /** The VIN read off the car is [vinCarId]'s; live data stays there. Nothing changes. */
        data class VinSaysOtherCar(val vinCarId: String) : AnswerPlan

        /** [carId] is not a car on file (archived or gone). Nothing changes. */
        data class NotOnFile(val carId: String) : AnswerPlan
    }

    companion object {
        const val PENDING_PREFIX = "pending:"
        private const val VIN_PREFIX = "pending:vin:"
        private const val DONGLE_PREFIX = "pending:obd:"

        fun pendingIdForDongle(mac: String, since: Long = 0L): String =
            DONGLE_PREFIX + mac + if (since > 0L) "@$since" else ""

        /** The dongle a dongle-keyed pending id was held for, or null for a VIN-keyed one. */
        fun macOfPending(id: String): String? =
            id.takeIf { it.startsWith(DONGLE_PREFIX) }?.removePrefix(DONGLE_PREFIX)?.substringBefore('@')

        /** The session start a dongle-keyed pending id carries, or 0. */
        fun sinceOfPending(id: String): Long =
            id.takeIf { it.startsWith(DONGLE_PREFIX) }?.substringAfter('@', "")?.toLongOrNull() ?: 0L

        /** Two ids name the same held session when they differ only in the session start. */
        fun sessionKey(id: String): String = if (id.startsWith(DONGLE_PREFIX)) id.substringBefore('@') else id

        fun isPending(id: String?): Boolean = id?.startsWith(PENDING_PREFIX) == true

        /** The VIN a pending id was keyed by, or null for a dongle-keyed one. */
        fun vinOfPending(id: String): String? = id.takeIf { it.startsWith(VIN_PREFIX) }?.removePrefix(VIN_PREFIX)

        /**
         * The pure half of [ActiveVehicle.current] (OBD VIN attribution, 2026-11-15). A connection
         * whose car is decided wins: the VIN is a fact read from the car, so it beats a stale hand
         * pick. A connection whose car is NOT known (held under a `pending:` id) is never "the
         * active car": questions go to the pick, else the default, and nothing is written onto a
         * guessed car. Undecided or unplugged keeps the old rule (pick, else the dongle's address).
         */
        fun activeCarId(selectedId: String?, connectedAddress: String?, dongleCar: DongleCar?): String =
            when (dongleCar) {
                is OnCar -> dongleCar.carId
                is Held -> selectedId ?: VehicleController.DEFAULT_VEHICLE_ID
                null -> selectedId ?: connectedAddress ?: VehicleController.DEFAULT_VEHICLE_ID
            }

        /** VINs compare upper case and trimmed; "" means none on file. */
        fun normalizeVin(vin: String?): String = vin?.trim()?.uppercase().orEmpty()

        /**
         * The car for a fresh connection.
         *  - A VIN on exactly one car on file: that car ([Basis.VIN]), even over a hand pick.
         *  - A VIN on no car: held ([HeldReason.UNKNOWN_VIN]); on two or more: held
         *    ([HeldReason.SHARED_VIN]). Never a guess.
         *  - No VIN: the one car on file whose learned fingerprint is exactly this connect's
         *    ([CarFingerprint], [Basis.ASSUMED]), else held ([HeldReason.NO_VIN]). Nothing is assumed
         *    from the adapter itself: one adapter moves between cars (Kevin, 2026-11-17).
         * [cars] are the cars on file that are not archived.
         */
        fun decide(
            mac: String,
            vinResult: VinResponse.Result,
            cars: List<CarVin>,
            fingerprintCar: String?,
            selectedId: String?,
        ): DongleCar {
            val vin = (vinResult as? VinResponse.Result.Vin)?.vin?.let { normalizeVin(it) }
            if (vin != null) {
                val owners = cars.filter { normalizeVin(it.vin) == vin }
                return when (owners.size) {
                    1 -> {
                        val carId = owners.single().carId
                        val overridden = selectedId?.takeIf { pick -> pick != carId && cars.any { it.carId == pick } }
                        OnCar(mac, carId, vin, Basis.VIN, overridden)
                    }
                    0 -> Held(mac, vin, HeldReason.UNKNOWN_VIN)
                    else -> Held(mac, vin, HeldReason.SHARED_VIN)
                }
            }
            val last = fingerprintCar?.takeIf { id -> cars.any { it.carId == id } }
            return if (last != null) OnCar(mac, last, null, Basis.ASSUMED) else Held(mac, null, HeldReason.NO_VIN)
        }

        /**
         * Kevin says the dongle's data on [subject] belongs to [chosenCarId]. [subject] is either the
         * live connection's state or a leftover pending id (a connection he never answered for).
         * [replaceVin] is his explicit "yes, replace the VIN on file".
         */
        @Suppress("ReturnCount") // one plan per case, in order
        fun planAnswer(
            subject: DongleCar,
            chosenCarId: String,
            cars: List<CarVin>,
            replaceVin: Boolean,
        ): AnswerPlan {
            val chosen = cars.firstOrNull { it.carId == chosenCarId } ?: return AnswerPlan.NotOnFile(chosenCarId)
            if (subject is OnCar && subject.carId == chosenCarId) {
                return AnswerPlan.Keep(chosenCarId, settles = subject.basis == Basis.ASSUMED)
            }
            // The VIN on file says another car. Only his explicit "replace" moves it (the VIN on
            // file may sit on the wrong car: a 2026-08 Jeep row held an Outlander's VIN).
            if (subject is OnCar && subject.basis == Basis.VIN && !replaceVin) {
                return AnswerPlan.VinSaysOtherCar(subject.carId)
            }
            val vin = when (subject) {
                is OnCar -> subject.vin
                is Held -> subject.vin
            }
            val chosenVin = normalizeVin(chosen.vin)
            val differentVinOnFile = vin != null && chosenVin.isNotEmpty() && chosenVin != vin
            if (differentVinOnFile && !replaceVin) return AnswerPlan.VinConflict(chosenCarId, chosenVin, vin.orEmpty())
            // A held connection's data never left the phone; a car's (ASSUMED, an earlier ANSWER, a
            // replaced VIN) is a correction.
            return AnswerPlan.Move(
                fromId = subject.recordingId,
                toCarId = chosenCarId,
                asCorrection = subject is OnCar,
                writeVin = vin?.takeIf { it != chosenVin },
                clearVinFrom = vin?.let { v -> otherOwners(cars, chosenCarId, v) }.orEmpty(),
            )
        }

        /** "VIN ending 8H9" - enough to tell cars apart in words without reading out all 17. */
        fun vinTail(vin: String): String = "VIN ending ${vin.takeLast(VIN_TAIL)}"

        private const val VIN_TAIL = 6
    }
}

private fun otherOwners(cars: List<DongleCar.CarVin>, chosenCarId: String, vin: String): List<String> =
    cars.filter { it.carId != chosenCarId && DongleCar.normalizeVin(it.vin) == vin }.map { it.carId }
