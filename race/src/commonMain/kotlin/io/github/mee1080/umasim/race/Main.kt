package io.github.mee1080.umasim.race

import io.github.mee1080.umasim.race.calc2.RaceSetting
import io.github.mee1080.umasim.race.calc2.RaceCalculator
import io.github.mee1080.umasim.race.calc2.SystemSetting
import io.github.mee1080.umasim.race.data2.SkillData
import io.github.mee1080.umasim.race.data2.skillData2
import io.github.mee1080.umasim.race.data2.loadSkillData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import java.io.PrintStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val acquiredSkillIds: List<Int>,
    val unacquiredSkillIds: List<Int>,
    val iterations: Int = 2000
)

@Serializable
data class RaceStats(
    val mean: Double,
    val stdev: Double,
    val min: Double,
    val max: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val whislo: Double,
    val whishi: Double,
    val fliers: List<Double>
)

@Serializable
data class CandidateResult(
    val stats: RaceStats,
    val timeSaved: Double
)

@Serializable
data class CliOutput(
    val baselineStats: RaceStats,
    val candidates: Map<String, CandidateResult>
)

// Helper function to crunch the numbers in Kotlin
fun List<Double>.calculateStats(): RaceStats {
    if (this.isEmpty()) return RaceStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
    
    val sorted = this.sorted()
    
    val mean = this.average()
    val min = sorted.first()
    val max = sorted.last()
    
    val q1 = sorted[(sorted.size * 0.25).toInt()]
    val median = sorted[(sorted.size * 0.5).toInt()]
    val q3 = sorted[(sorted.size * 0.75).toInt()]
    
    // Calculate IQR bounds for outliers (1.5x IQR is the standard boxplot math)
    val iqr = q3 - q1
    val lowerBound = q1 - 1.5 * iqr
    val upperBound = q3 + 1.5 * iqr
    
    // Separate the outliers from the main dataset
    val fliers = sorted.filter { it < lowerBound || it > upperBound }
    val nonFliers = sorted.filter { it in lowerBound..upperBound }
    
    // The whiskers stop at the furthest data point that ISN'T an outlier
    val whislo = nonFliers.firstOrNull() ?: min
    val whishi = nonFliers.lastOrNull() ?: max
    
    // Sample Standard Deviation
    val variance = this.sumOf { (it - mean) * (it - mean) } / maxOf(1, this.size - 1)
    val stdev = sqrt(variance)
    
    return RaceStats(mean, stdev, min, max, q1, median, q3, whislo, whishi, fliers)
}

suspend fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8.name()))

    if (args.isEmpty()) return

    val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    try {
        loadSkillData()
        val payload = jsonParser.decodeFromString<CliInput>(args[0])
        
        val results = runSimulation(
            payload.baseSetting,
            payload.acquiredSkillIds,
            payload.unacquiredSkillIds,
            payload.iterations
        )
        
        println(jsonParser.encodeToString(results))
        
    } catch (e: Exception) {
        System.err.println("{\"error\": \"${e.message}\"}")
    }
}

suspend fun runSimulation(
    baseSetting: RaceSetting, 
    acquiredSkillIds: List<Int>, 
    unacquiredSkillIds: List<Int>, 
    iterations: Int
): CliOutput = coroutineScope {
    
    val systemSetting = SystemSetting()
    val acquiredStr = acquiredSkillIds.map { it.toString() }
    val unacquiredStr = unacquiredSkillIds.map { it.toString() }
    
    val baseSkills = skillData2.filter { acquiredStr.contains(it.id) }
    
    val baselineSetting = baseSetting.copy(
        umaStatus = baseSetting.umaStatus.copy(hasSkills = baseSkills)
    )

    // Generate isolated timelines for mechanics and skills
    val globalRaceSeed = System.currentTimeMillis()
    val globalSkillSeed = globalRaceSeed xor 0x9A7F3C1L 

    val raceSeeds = List(iterations) { index -> globalRaceSeed + index }
    val skillSeeds = List(iterations) { index -> globalSkillSeed + index }

    // Dynamically limit threads to (Total Cores - 1)
    val targetCores = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
    val simDispatcher = Dispatchers.Default.limitedParallelism(targetCores)

    // Run Baseline
    val baselineTimes = raceSeeds.zip(skillSeeds).map { (currentSeed, currentSkillSeed) ->
        async(simDispatcher) { 
            val calculator = RaceCalculator(systemSetting, currentSeed, currentSkillSeed)
            calculator.simulate(baselineSetting).first.raceTime.toDouble() 
        }
    }.awaitAll()
    
    val baselineStats = baselineTimes.calculateStats()
    
    val results = mutableMapOf<String, CandidateResult>()
    val candidateSkills = skillData2.filter { unacquiredStr.contains(it.id) }

    // Run Candidates
    for (candidate in candidateSkills) {
        val testSetting = baselineSetting.copy(
            umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + candidate)
        )
        
        val testTimes = raceSeeds.zip(skillSeeds).map { (currentSeed, currentSkillSeed) ->
            async(simDispatcher) {
                val calculator = RaceCalculator(systemSetting, currentSeed, currentSkillSeed)
                calculator.simulate(testSetting).first.raceTime.toDouble()
            }
        }.awaitAll()
        
        val candidateStats = testTimes.calculateStats()
        
        results[candidate.id] = CandidateResult(
            stats = candidateStats,
            timeSaved = baselineStats.mean - candidateStats.mean
        )
    }
    
    return@coroutineScope CliOutput(baselineStats, results)
}