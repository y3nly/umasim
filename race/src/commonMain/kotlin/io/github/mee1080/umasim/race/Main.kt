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

@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val acquiredSkillIds: List<Int>,
    val unacquiredSkillIds: List<Int>,
    val iterations: Int = 2000
)

@Serializable
data class CliOutput(
    val baselineStats: BoxPlotStats,
    val candidates: Map<String, CandidateResult>
)

@Serializable
data class BoxPlotStats(
    val mean: Double,
    val median: Double,
    val stdev: Double,
    val min: Double,
    val max: Double,
    val q1: Double, 
    val q3: Double,
    val whiskerMin: Double,
    val whiskerMax: Double,
    val outliers: List<Double>
)

@Serializable
data class HistogramStats(
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
    val raceTimeStats: BoxPlotStats,
    val timeSavedStats: HistogramStats
)

/**
 * 📦 Extension function for calculating Box Plot statistics (Race Times)
 */
fun List<Double>.calculateBoxPlotStats(): BoxPlotStats {
    if (this.isEmpty()) return BoxPlotStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
    
    val size = this.size
    val sorted = this.sorted()
    
    val median = if (size % 2 == 0) {
        (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
    } else {
        sorted[size / 2]
    }
    
    fun getPercentile(p: Double): Double {
        if (size == 1) return sorted[0]
        val index = p * (size - 1)
        val lower = kotlin.math.floor(index).toInt()
        val upper = kotlin.math.ceil(index).toInt()
        val weight = index - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    val q1 = getPercentile(0.25)
    val q3 = getPercentile(0.75)
    
    val iqr = q3 - q1
    val lowerFence = q1 - 1.5 * iqr
    val upperFence = q3 + 1.5 * iqr
    
    // Outliers are everything strictly outside the fences
    val outliers = sorted.filter { it < lowerFence || it > upperFence }
    
    // Whiskers are the extreme data points that stay inside the fences
    val whiskerMin = sorted.first { it >= lowerFence }
    val whiskerMax = sorted.last { it <= upperFence }
    
    val mean = this.average()
    val absoluteMin = sorted.first()
    val absoluteMax = sorted.last()
    
    val variance = this.sumOf { (it - mean) * (it - mean) } / maxOf(1, size - 1)
    val stdev = kotlin.math.sqrt(variance)
    
    return BoxPlotStats(
        mean = mean, 
        median = median, 
        stdev = stdev, 
        min = absoluteMin, 
        max = absoluteMax, 
        whiskerMin = whiskerMin,
        whiskerMax = whiskerMax,
        q1 = q1, 
        q3 = q3, 
        outliers = outliers
    )
}

/**
 * 📊 Extension function for calculating Histogram statistics (Time Saved)
 */
fun List<Double>.calculateHistogramStats(fixedBinWidth: Double = 0.01): HistogramStats {
    if (this.isEmpty()) return HistogramStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
    
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
    
    val halfWidth = fixedBinWidth / 2.0
    val minBinIndex = kotlin.math.floor((min + halfWidth) / fixedBinWidth).toInt()
    val maxBinIndex = kotlin.math.floor((max + halfWidth) / fixedBinWidth).toInt()
    
    val actualBinCount = maxOf(1, maxBinIndex - minBinIndex + 1)
    val frequencies = IntArray(actualBinCount)
    
    for (value in this) {
        var binIndex = kotlin.math.floor((value + halfWidth) / fixedBinWidth).toInt() - minBinIndex
        if (binIndex >= actualBinCount) binIndex = actualBinCount - 1
        if (binIndex < 0) binIndex = 0 
        frequencies[binIndex]++
    }
    
    val alignedBinMin = (minBinIndex * fixedBinWidth) - halfWidth
    
    return HistogramStats(mean, median, stdev, min, max, alignedBinMin, fixedBinWidth, frequencies.toList())
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
    val raceSeeds = List(iterations) { index -> globalRaceSeed + index }

    val targetCores = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
    val simDispatcher = Dispatchers.Default.limitedParallelism(targetCores)

    val baselineTimes = raceSeeds.map { currentSeed ->
        async(simDispatcher) { 
            val calculator = RaceCalculator(systemSetting, currentSeed)
            calculator.simulate(baselineSetting).first.raceTime.toDouble() 
        }
    }.awaitAll()
    
    val baselineStats = baselineTimes.calculateBoxPlotStats()
    val results = mutableMapOf<String, CandidateResult>()
    val candidateSkills = skillData2.filter { unacquiredStr.contains(it.id) }

    for (candidate in candidateSkills) {
        val testSetting = baselineSetting.copy(umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + candidate))
        val testTimes = raceSeeds.map { currentSeed ->
            async(simDispatcher) {
                val calculator = RaceCalculator(systemSetting, currentSeed)
                calculator.simulate(testSetting).first.raceTime.toDouble()
            }
        }.awaitAll()
        
        val candidateRaceStats = testTimes.calculateBoxPlotStats()
        
        val timeSavedArray = baselineTimes.zip(testTimes).map { (base, test) -> test - base }
        val candidateSavedStats = timeSavedArray.calculateHistogramStats()
        
        results[candidate.id] = CandidateResult(raceTimeStats = candidateRaceStats, timeSavedStats = candidateSavedStats)
    }
    return@coroutineScope CliOutput(baselineStats, results)
}