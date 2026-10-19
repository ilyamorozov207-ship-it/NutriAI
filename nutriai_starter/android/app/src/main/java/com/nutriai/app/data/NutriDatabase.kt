package com.nutriai.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Meal::class], version = 1, exportSchema = false)
abstract class NutriDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        @Volatile private var INSTANCE: NutriDatabase? = null
        fun get(context: Context): NutriDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NutriDatabase::class.java,
                "nutriai.db"
            ).build().also { INSTANCE = it }
        }
    }
}
