package scheduling.procedures;

import gov.nasa.ammos.aerie.procedural.scheduling.ActivityAutoDelete;
import gov.nasa.ammos.aerie.procedural.scheduling.Goal;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.SchedulingProcedure;
import gov.nasa.ammos.aerie.procedural.scheduling.annotations.WithDefaults;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.EditablePlan;
import gov.nasa.ammos.aerie.procedural.scheduling.plan.NewDirective;
import gov.nasa.ammos.aerie.procedural.timeline.Interval;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Booleans;
import gov.nasa.ammos.aerie.procedural.timeline.collections.profiles.Real;
import gov.nasa.ammos.aerie.procedural.timeline.plan.Plan;
import gov.nasa.ammos.aerie.procedural.timeline.plan.SimulationResults;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.AnyDirective;
import gov.nasa.ammos.aerie.procedural.timeline.payloads.activities.DirectiveStart;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.DurationValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.EnumValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import missionmodel.radar.RadarDataCollectionMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;

/**
 * Schedules a priority-based set of activities within a 24-hour period.
 *
 * Priority order:
 * 1. Activity A (ChangeRadarDataMode) - must occur at beginning of period
 * 2. Activity B (SolarArrayDeployment) - can occur any time
 * 3. Activity C (Radar_On) - at/below altitude threshold
 * 4. Activity D (ReprioritizeData) - optional, after C
 * 5. Activity E (Downlink) - fallback if D cannot be scheduled
 * 6. Activity F (GenerateData) - close to C (anchored) to minimize slew
 * 7. Activity G (EnterOccultation) - at exact specified time
 */
@SchedulingProcedure
public record SchedulePriorityActivities(
    Duration periodStart,
    Duration periodDuration,
    double altitudeThreshold,
    Duration activityGTime,
    Duration minReprioritizeDuration
) implements Goal {

  @NotNull
  @Override
  public ActivityAutoDelete shouldDeletePastCreations(
      @NotNull final Plan plan,
      @Nullable final SimulationResults simResults)
  {
    // Delete activities created by previous runs of this goal, using Cascade to handle anchored activities
    return new ActivityAutoDelete.JustBefore(DeletedAnchorStrategy.Cascade);
  }

  @WithDefaults
  public static class Template {
    public Duration periodStart = ZERO;
    public Duration periodDuration = Duration.of(24, HOUR);
    public double altitudeThreshold = 500.0; // km
    public Duration activityGTime = Duration.of(13, HOUR);
    public Duration minReprioritizeDuration = Duration.of(30, MINUTE);
  }

  @Override
  public void run(EditablePlan plan) {
    // Define period bounds
    Duration periodEnd = periodStart.plus(periodDuration);

    // Ensure we have simulation results for altitude resource
    var simResults = plan.latestResults();
    if (simResults == null) {
      simResults = plan.simulate();
    }

    // ===== ACTIVITY A: ChangeRadarDataMode at beginning of period =====
    plan.create(new NewDirective(
        new AnyDirective(Map.of(
            "mode", new EnumValueMapper<>(RadarDataCollectionMode.class)
                .serializeValue(RadarDataCollectionMode.LOW_RES)
        )),
        "ActivityA_ChangeRadarMode",
        "ChangeRadarDataMode",
        new DirectiveStart.Absolute(periodStart)
    ));

    // ===== ACTIVITY B: SolarArrayDeployment any time (place at mid-period) =====
    Duration activityBTime = periodStart.plus(periodDuration.dividedBy(2));
    plan.create(new NewDirective(
        new AnyDirective(Map.of(
            "deployDuration", SerializedValue.of(30.0)  // 30 minutes
        )),
        "ActivityB_SolarArrayDeployment",
        "SolarArrayDeployment",
        new DirectiveStart.Absolute(activityBTime)
    ));

    // ===== ACTIVITY C: Radar_On at/below altitude threshold =====
    var altitude = simResults.resource("SpacecraftAltitude_MARS", Real.deserializer()).cache();

    // Debug: Log altitude at key times to help diagnose threshold issues
    Double altAtStart = altitude.sample(periodStart);
    Double altAtMid = altitude.sample(periodStart.plus(periodDuration.dividedBy(2)));
    Double altAtEnd = altitude.sample(periodEnd);
    System.out.println("SchedulePriorityActivities: Altitude samples - start=" + altAtStart + " km, mid=" + altAtMid + " km, end=" + altAtEnd + " km, threshold=" + altitudeThreshold + " km");

    Booleans lowAltitude = altitude.lessThan(altitudeThreshold);

    Duration activityCTime = null;
    ActivityDirectiveId activityCId = null;

    for (Interval window : lowAltitude.highlightTrue()) {
      // Find first low-altitude window that overlaps with our period
      // Use the later of window.start or periodStart to handle case where we start inside a low-altitude window
      if (window.end.longerThan(periodStart) && window.start.shorterThan(periodEnd)) {
        activityCTime = window.start.longerThan(periodStart) ? window.start : periodStart;
        System.out.println("SchedulePriorityActivities: Found low-altitude window [" + window.start + " to " + window.end + "], placing Activity C at " + activityCTime);
        var activityCDirective = new NewDirective(
            new AnyDirective(Map.of()),
            "ActivityC_RadarOn_AltitudeBased",
            "Radar_On",
            new DirectiveStart.Absolute(activityCTime)
        );
        activityCId = plan.create(activityCDirective);
        break;
      }
    }

    if (activityCId == null) {
      System.out.println("SchedulePriorityActivities: No low-altitude window found below threshold " + altitudeThreshold + " km in period. Activity C not scheduled.");
    }

    // ===== ACTIVITY F: GenerateData anchored to C (if C was scheduled) =====
    if (activityCId != null) {
      plan.create(new NewDirective(
          new AnyDirective(Map.of(
              "bin", SerializedValue.of(0),
              "rate", SerializedValue.of(Map.of(
                  "present", SerializedValue.of(true),
                  "value", SerializedValue.of(100.0)
              )),
              "duration", SerializedValue.of(Map.of(
                  "present", SerializedValue.of(true),
                  "value", new DurationValueMapper().serializeValue(Duration.of(10, MINUTE))
              ))
          )),
          "ActivityF_GenerateData_MinimizeSlew",
          "GenerateData",
          new DirectiveStart.Anchor(
              activityCId,
              Duration.of(1, MINUTE),  // Minimal offset
              DirectiveStart.Anchor.AnchorPoint.End
          )
      ));
    }

    // ===== ACTIVITY D: ReprioritizeData (optional, after C) =====
    boolean activityDScheduled = false;

    if (activityCTime != null) {
      // Calculate remaining time after C
      Duration timeAfterC = periodEnd.minus(activityCTime);

      // Check if we have enough time for reprioritization
      if (timeAfterC.longerThan(minReprioritizeDuration)) {
        plan.create(new NewDirective(
            new AnyDirective(Map.of(
                "volume", SerializedValue.of(1000.0),  // bits to reprioritize
                "bin", SerializedValue.of(0),         // source bin
                "newBin", SerializedValue.of(1)       // destination bin
            )),
            "ActivityD_ReprioritizeData",
            "ReprioritizeData",
            new DirectiveStart.Anchor(
                activityCId,
                Duration.of(5, MINUTE),  // Start 5 min after C
                DirectiveStart.Anchor.AnchorPoint.End
            )
        ));
        activityDScheduled = true;
      }
    }

    // ===== ACTIVITY E: Downlink (fallback if D not scheduled) =====
    if (!activityDScheduled) {
      // Place at 3/4 through the period as a fallback
      Duration activityETime = periodStart.plus(periodDuration.times(3).dividedBy(4));
      plan.create(new NewDirective(
          new AnyDirective(Map.of(
              "duration", new DurationValueMapper().serializeValue(Duration.of(30, MINUTE)),
              "bitRate", SerializedValue.of(1000)
          )),
          "ActivityE_Downlink_Fallback",
          "Downlink",
          new DirectiveStart.Absolute(activityETime)
      ));
    }

    // ===== ACTIVITY G: EnterOccultation at exact specified time =====
    Duration activityGAbsoluteTime = periodStart.plus(activityGTime);
    if (activityGAbsoluteTime.shorterThan(periodEnd)) {
      plan.create(new NewDirective(
          new AnyDirective(Map.of(
              "body", SerializedValue.of("MARS"),
              "station", SerializedValue.of("DSS-24")
          )),
          "ActivityG_EnterOccultation_ExactTime",
          "EnterOccultation",
          new DirectiveStart.Absolute(activityGAbsoluteTime)
      ));
    }

    // Commit all activities
    plan.commit();
  }
}
