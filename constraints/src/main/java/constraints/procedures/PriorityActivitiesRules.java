package constraints.procedures;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;
import gov.nasa.ammos.aerie.procedural.constraints.Violation;
import gov.nasa.ammos.aerie.procedural.constraints.Violations;
import gov.nasa.ammos.aerie.procedural.constraints.annotations.ConstraintProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.WithDefaults;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.ArrayList;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

/**
 * Validates that the priority activities scheduling rules are satisfied:
 * 1. Activity A (ChangeRadarDataMode) - must exist at beginning of period
 * 2. Activity B (SolarArrayDeployment) - must exist within the period
 * 3. Activity C (Radar_On) - if altitude drops below threshold, must have Radar_On
 * 4. Activity D vs E - D and E should not both be present (E is fallback for D)
 * 5. Activity F (GenerateData) - if C exists, F must exist and be close to C
 * 6. Activity G (EnterOccultation) - must be at specified time
 */
@ConstraintProcedure
public record PriorityActivitiesRules(
    Duration periodStart,
    Duration periodDuration,
    double altitudeThreshold,
    Duration activityGTime,
    Duration maxSlewTime
) implements Constraint {

  @WithDefaults
  public static class Template {
    public Duration periodStart = ZERO;
    public Duration periodDuration = Duration.of(24, HOUR);
    public double altitudeThreshold = 500.0; // km
    public Duration activityGTime = Duration.of(13, HOUR);
    public Duration maxSlewTime = Duration.of(5, MINUTE); // Max time between C and F
  }

  @Override
  public Violations run(Plan plan, SimulationResults simResults) {
    List<Violation> violations = new ArrayList<>();
    Duration periodEnd = periodStart.plus(periodDuration);
    Interval period = Interval.between(periodStart, periodEnd);

    // Get directives by their names (as assigned by SchedulePriorityActivities goal)
    var allDirectives = plan.directives().collect();

    // Match activities by type (note: custom names from NewDirective aren't preserved by Aerie)
    // Activity A = ChangeRadarDataMode at period start
    var activityA = allDirectives.stream()
        .filter(d -> "ChangeRadarDataMode".equals(d.getType()))
        .filter(d -> d.getStartTime().equals(periodStart))
        .findFirst();

    // Activity B = SolarArrayDeployment (any time in period)
    var activityB = allDirectives.stream()
        .filter(d -> "SolarArrayDeployment".equals(d.getType()))
        .filter(d -> !d.getStartTime().shorterThan(periodStart) && d.getStartTime().shorterThan(periodEnd))
        .findFirst();

    // Activity C = Radar_On (should be during low altitude)
    var activityC = allDirectives.stream()
        .filter(d -> "Radar_On".equals(d.getType()))
        .filter(d -> !d.getStartTime().shorterThan(periodStart) && d.getStartTime().shorterThan(periodEnd))
        .findFirst();

    // Activity D = ReprioritizeData (optional, after C)
    var activityD = allDirectives.stream()
        .filter(d -> "ReprioritizeData".equals(d.getType()))
        .filter(d -> !d.getStartTime().shorterThan(periodStart) && d.getStartTime().shorterThan(periodEnd))
        .findFirst();

    // Activity E = Downlink (fallback if D not scheduled)
    var activityE = allDirectives.stream()
        .filter(d -> "Downlink".equals(d.getType()))
        .filter(d -> !d.getStartTime().shorterThan(periodStart) && d.getStartTime().shorterThan(periodEnd))
        .findFirst();

    // Activity F = GenerateData (should be close to C)
    // Note: GenerateData is anchored to C, so its directive start time may be unresolved (default 0).
    // Use simulation instances to get the actual resolved time.
    var generateDataInstances = simResults.instances("GenerateData").collect();
    var activityFInstance = generateDataInstances.stream()
        .filter(inst -> !inst.getStartTime().shorterThan(periodStart) && inst.getStartTime().shorterThan(periodEnd))
        .findFirst();

    // Activity G = EnterOccultation at exact specified time
    Duration expectedGTime = periodStart.plus(activityGTime);
    var activityG = allDirectives.stream()
        .filter(d -> "EnterOccultation".equals(d.getType()))
        .filter(d -> !d.getStartTime().shorterThan(periodStart) && d.getStartTime().shorterThan(periodEnd))
        .findFirst();

    // Rule 1: Activity A must exist at beginning of period
    if (activityA.isEmpty()) {
      violations.add(new Violation(
          Interval.at(periodStart),
          "Rule 1 violated: Activity A (ChangeRadarDataMode) missing at beginning of period",
          List.of()
      ));
    } else if (!activityA.get().getStartTime().equals(periodStart)) {
      violations.add(new Violation(
          Interval.at(activityA.get().getStartTime()),
          "Rule 1 violated: Activity A (ChangeRadarDataMode) not at beginning of period (expected " + periodStart + ", found " + activityA.get().getStartTime() + ")",
          List.of(activityA.get().id)
      ));
    }

    // Rule 2: Activity B must exist within the period
    if (activityB.isEmpty()) {
      violations.add(new Violation(
          period,
          "Rule 2 violated: Activity B (SolarArrayDeployment) missing from period",
          List.of()
      ));
    }

    // Rule 3: If there's a low-altitude window, Activity C should exist
    var altitude = simResults.resource("SpacecraftAltitude_MARS", Real.deserializer()).cache();
    var lowAltitudeWindows = altitude.lessThan(altitudeThreshold).highlightTrue();
    boolean hasLowAltitudeInPeriod = false;

    for (var window : lowAltitudeWindows) {
      if (window.end.longerThan(periodStart) && window.start.shorterThan(periodEnd)) {
        hasLowAltitudeInPeriod = true;
        break;
      }
    }

    if (hasLowAltitudeInPeriod && activityC.isEmpty()) {
      violations.add(new Violation(
          period,
          "Rule 3 violated: Low-altitude window exists but Activity C (Radar_On) is missing",
          List.of()
      ));
    }

    // Rule 4: Activity D and E should not both be present (E is fallback)
    if (activityD.isPresent() && activityE.isPresent()) {
      violations.add(new Violation(
          Interval.between(activityD.get().getStartTime(), activityE.get().getStartTime()),
          "Rule 4 violated: Both Activity D (ReprioritizeData) and Activity E (Downlink fallback) are present - E should only exist if D cannot be scheduled",
          List.of(activityD.get().id, activityE.get().id)
      ));
    }

    // Rule 5: If Activity C exists, Activity F must exist and be close to C (minimize slew)
    if (activityC.isPresent()) {
      if (activityFInstance.isEmpty()) {
        violations.add(new Violation(
            Interval.at(activityC.get().getStartTime()),
            "Rule 5 violated: Activity C (Radar_On) exists but Activity F (GenerateData) is missing",
            List.of(activityC.get().id)
        ));
      } else {
        // Check if F is close to C's END (within maxSlewTime)
        // Both C and F use simulation instances to get actual resolved times
        var cDirectiveId = activityC.get().id;
        Duration fStartTime = activityFInstance.get().getStartTime();

        // Find C's simulated instance to get actual end time
        var radarOnInstances = simResults.instances("Radar_On").collect();
        var cInstance = radarOnInstances.stream()
            .filter(inst -> inst.directiveId != null && inst.directiveId.equals(cDirectiveId))
            .findFirst();

        if (cInstance.isPresent()) {
          // Use actual end time from simulation
          Duration cEndTime = cInstance.get().getInterval().end;
          Duration gapAfterC = fStartTime.minus(cEndTime);

          // F should start after C ends and within maxSlewTime of C's end
          if (gapAfterC.isNegative() || gapAfterC.longerThan(maxSlewTime)) {
            // Get F's directive ID for the violation (may be null for child activities)
            var fDirectiveId = activityFInstance.get().directiveId;
            var violationIds = fDirectiveId != null
                ? List.of(activityC.get().id, fDirectiveId)
                : List.of(activityC.get().id);
            violations.add(new Violation(
                Interval.between(cEndTime, fStartTime),
                "Rule 5 violated: Activity F (GenerateData) should start within " + maxSlewTime + " after Activity C (Radar_On) ends (found gap of " + gapAfterC + ")",
                violationIds
            ));
          }
        } else {
          // Fallback: can't find C's simulated instance
          Duration cStartTime = activityC.get().getStartTime();
          Duration timeDiff = fStartTime.minus(cStartTime);
          if (timeDiff.isNegative()) {
            violations.add(new Violation(
                Interval.between(cStartTime, fStartTime),
                "Rule 5 violated: Activity F (GenerateData) starts before Activity C (Radar_On)",
                List.of(activityC.get().id)
            ));
          }
        }
      }
    }

    // Rule 6: Activity G must be at exactly the specified time
    // expectedGTime already declared above (line 93)
    if (expectedGTime.shorterThan(periodEnd)) {
      if (activityG.isEmpty()) {
        violations.add(new Violation(
            Interval.at(expectedGTime),
            "Rule 6 violated: Activity G (EnterOccultation) missing at specified time " + expectedGTime,
            List.of()
        ));
      } else if (!activityG.get().getStartTime().equals(expectedGTime)) {
        violations.add(new Violation(
            Interval.at(activityG.get().getStartTime()),
            "Rule 6 violated: Activity G (EnterOccultation) not at specified time (expected " + expectedGTime + ", found " + activityG.get().getStartTime() + ")",
            List.of(activityG.get().id)
        ));
      }
    }

    return new Violations(violations);
  }
}
