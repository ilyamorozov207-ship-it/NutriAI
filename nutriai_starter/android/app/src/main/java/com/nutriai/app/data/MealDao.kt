package com.nutriai.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {
    @Query("SELECT * FROM meals ORDER BY createdAt DESC")
    fun observeMeals(): Flow<List<Meal>>

    @Insert
    suspend fun insert(meal: Meal)

    @Update
    suspend fun update(meal: Meal)

    @Insert
    suspend fun insertAll(meals: List<Meal>)

    @Query("SELECT * FROM meals ORDER BY createdAt DESC")
    suspend fun getAllMeals(): List<Meal>

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM meals")
    suspend fun deleteAll()

    /** Replace all diary entries atomically during backup restoration. */
    @Transaction
    suspend fun replaceAll(meals: List<Meal>) {
        deleteAll()
        if (meals.isNotEmpty()) insertAll(meals)
    }
}
