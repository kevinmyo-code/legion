package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Data Access Object for [CompanionProfileEntity]. Kept simple and
 * inspectable, same rationale as [IngestedFileDao]'s doc comment: the
 * roster/active-selection UI (Part 2) and materialisation
 * (`ai/ActiveCompanionProfile.kt`) drive everything, this DAO just stores rows.
 */
@Dao
interface CompanionProfileDao {
    /** The full roster, newest edit first - for a future picker/roster screen (Part 2). */
    @Query("SELECT * FROM companion_profiles ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CompanionProfileEntity>

    @Query("SELECT * FROM companion_profiles WHERE profileId = :profileId")
    suspend fun getById(profileId: String): CompanionProfileEntity?

    /** Insert-or-replace by [CompanionProfileEntity.profileId] - the one write path for a profile edit. */
    @Upsert
    suspend fun upsert(profile: CompanionProfileEntity)

    @Query("SELECT COUNT(*) FROM companion_profiles")
    suspend fun count(): Int
}
