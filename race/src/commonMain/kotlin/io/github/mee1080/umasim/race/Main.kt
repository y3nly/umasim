package io.github.mee1080.umasim.cli

import io.github.mee1080.umasim.race.calc2.RaceSetting
import io.github.mee1080.umasim.race.calc2.RaceCalculator
import io.github.mee1080.umasim.race.calc2.SystemSetting
import io.github.mee1080.umasim.race.data2.SkillData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

// 1. The JSON Payload Wrapper
// This dictates the exact shape of the dictionary your Python tool needs to send.
@Serializable
data class CliInput(
    val baseSetting: RaceSetting,
    val allScrapedSkills: List<SkillData>,
    val targetSkillIds: List<String>,
    val iterations: Int = 100
)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("{\"error\": \"No JSON payload provided\"}")
        return
    }

    val inputJson = args[0]
    
    // Lenient parser to ignore any extra fields your Python tool might accidentally send
    val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    try {
        // 2. Decode the Python payload
        val payload = jsonParser.decodeFromString<CliInput>(inputJson)
        
        // 3. Crunch the numbers
        val results = calculateSkillDifferentials(
            payload.baseSetting,
            payload.allScrapedSkills,
            payload.targetSkillIds,
            payload.iterations
        )
        
        // 4. Fire the results back to Python stdout
        println(jsonParser.encodeToString(results))
        
    } catch (e: Exception) {
        System.err.println("{\"error\": \"Simulation failed: ${e.message}\"}")
    }
}

/**
 * Calculates the exact time saved for each targeted skill via A/B testing.
 */
fun calculateSkillDifferentials(
    baseSetting: RaceSetting, 
    allScrapedSkills: List<SkillData>, 
    targetSkillIds: List<String>, 
    iterations: Int
): Map<String, Double> {
    val calculator = RaceCalculator(SystemSetting())
    
    // Strip the target skills to create a pure baseline
    val baseSkills = allScrapedSkills.filterNot { targetSkillIds.contains(it.id.toString()) }
    val baselineSetting = baseSetting.copy(
        umaStatus = baseSetting.umaStatus.copy(hasSkills = baseSkills)
    )
    
    // Run the baseline simulations
    val baseTimes = List(iterations) { calculator.simulate(baselineSetting).first.raceTime }
    val avgBaseTime = baseTimes.average()
    
    val differentials = mutableMapOf<String, Double>()
    
    // A/B Test each target skill
    for (skillId in targetSkillIds) {
        val targetSkill = allScrapedSkills.find { it.id.toString() == skillId } ?: continue
        
        // Add ONLY this specific target skill back into the baseline
        val testSetting = baselineSetting.copy(
            umaStatus = baselineSetting.umaStatus.copy(hasSkills = baseSkills + targetSkill)
        )
        
        // Run the simulations for this specific skill
        val testTimes = List(iterations) { calculator.simulate(testSetting).first.raceTime }
        val avgTestTime = testTimes.average()
        
        // Calculate time saved (positive number = race was faster)
        differentials[targetSkill.name] = avgBaseTime - avgTestTime
    }
    
    return differentials
}