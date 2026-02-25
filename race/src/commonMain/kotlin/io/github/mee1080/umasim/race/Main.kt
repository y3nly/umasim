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
import kotlin.math.round

@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val acquiredSkillIds: List<Int>,
    val unacquiredSkillIds: List<Int>,
    val iterations: Int = 2000
)

@Serializable
data class CliOutput(
    val baselineStats: RaceStats,
    val candidates: Map<String, CandidateResult>
)

@Serializable
data class RaceStats(
    val mean: Double,
    val median: Double,
    val stdev: Double,
    val min: Double,
    val max: Double,
    val binMin: Double,
    val binWidth: Double,
    val frequencies: List<Int>
)

@Serializable
data class CandidateResult(
    val raceTimeStats: RaceStats,
    val timeSavedStats: RaceStats
)

/**
 * Extension function to calculate zero-anchored histogram bins, Mean, and Median.
 */
fun List<Double>.calculateStats(targetBinCount: Int = 20): RaceStats {
    if (this.isEmpty()) return RaceStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
    
    val size = this.size
    val sorted = this.sorted()
    
    val median = if (size % 2 == 0) {
        (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
    } else {
        sorted[size / 2]
    }
    
    val mean = this.average()
    val min = sorted.first()
    val max = sorted.last()
    
    val variance = this.sumOf { (it - mean) * (it - mean) } / maxOf(1, size - 1)
    val stdev = kotlin.math.sqrt(variance)
    
    // Safety net: Prevent division-by-zero
    val actualMin = if (min == max) min - 0.01 else min
    val actualMax = if (min == max) max + 0.01 else max
    
    // Calculate the target width of each bin
    val targetBinWidth = (actualMax - actualMin) / targetBinCount
    
    // Snapping to 0.0 grid
    val minBinIndex = round(actualMin / targetBinWidth).toInt()
    val maxBinIndex = round(actualMax / targetBinWidth).toInt()
    
    val actualBinCount = maxBinIndex - minBinIndex + 1
    val frequencies = IntArray(actualBinCount)
    
    for (value in this) {
        var binIndex = round(value / targetBinWidth).toInt() - minBinIndex
        if (binIndex >= actualBinCount) binIndex = actualBinCount - 1
        if (binIndex < 0) binIndex = 0 
        frequencies[binIndex]++
    }
    
    val alignedBinMin = minBinIndex * targetBinWidth - (targetBinWidth / 2.0)
    
    return RaceStats(mean, median, stdev, min, max, alignedBinMin, targetBinWidth, frequencies.toList())
}

suspend fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8.name()))
    if (args.isEmpty()) return
    val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    try {
        loadSkillData()
        val payload = jsonParser.decodeFromString<CliInput>(args[0])
        val results = runSimulation(payload.baseSetting, payload.acquiredSkillIds, payload.unacquiredSkillIds, payload.iterations)
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
    val baselineSetting = baseSetting.copy(umaStatus = baseSetting.umaStatus.copy(hasSkills = baseSkills))

    val globalRaceSeed = System.currentTimeMillis()
    val globalSkillSeed = globalRaceSeed xor 0x9A7F3C1L 
    val raceSeeds = List(iterations) { index -> globalRaceSeed + index }
    val skillSeeds = List(iterations) { index -> globalSkillSeed + index }

    val targetCores = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
    val simDispatcher = Dispatchers.Default.limitedParallelism(targetCores)

    val baselineTimes = raceSeeds.zip(skillSeeds).map { (currentSeed, currentSkillSeed) ->
        async(simDispatcher) { 
            val calculator = RaceCalculator(systemSetting, currentSeed, currentSkillSeed)
            calculator.simulate(baselineSetting).first.raceTime.toDouble() 
        }
    }.awaitAll()
    
    val baselineStats = baselineTimes.calculateStats(20)
    val results = mutableMapOf<String, CandidateResult>()
    val candidateSkills = skillData2.filter { unacquiredStr.contains(it.id) }

    for (candidate in candidateSkills) {
        val testSetting = baselineSetting.copy(umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + candidate))
        val testTimes = raceSeeds.zip(skillSeeds).map { (currentSeed, currentSkillSeed) ->
            async(simDispatcher) {
                val calculator = RaceCalculator(systemSetting, currentSeed, currentSkillSeed)
                calculator.simulate(testSetting).first.raceTime.toDouble()
            }
        }.awaitAll()
        
        val candidateRaceStats = testTimes.calculateStats(20)
        val timeSavedArray = baselineTimes.zip(testTimes).map { (base, test) -> test - base }
        val candidateSavedStats = timeSavedArray.calculateStats(20)
        
        results[candidate.id] = CandidateResult(raceTimeStats = candidateRaceStats, timeSavedStats = candidateSavedStats)
    }
    return@coroutineScope CliOutput(baselineStats, results)
}