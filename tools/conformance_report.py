"""Render requirement -> implementation -> executed test evidence, without inferring full conformance."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

# Representative executable evidence, not a claim that one test exhausts a scenario.
MOBILE = {
    "M01": ("LocalClient", "LocalRuntimeTest", "offlineMissingEndpointsStayOfflineAndUnavailableApisAreExplicit"),
    "M02": ("Persistence", "PersistenceTest", "cacheNeverConfirmsOnlineReadinessAndLocalSourcesNeverWriteIt"),
    "M03": ("OnlineSync", "OnlineSyncTest", "readinessTimeoutDoesNotStopRecoveryAndTerminalRejectionCannotBeReset"),
    "M04": ("LocalClient", "LocalRuntimeTest", "readyRequiresCommitAndExactRemoteBaseline"),
    "M05": ("PlatformState", "PlatformLifecycleTest", "backgroundCreationNeverConnectsEvenWithGraceAndWaitExpiresBeforeResume"),
    "M06": ("OnlineSync", "PlatformLifecycleTest", "graceRetainsOnlyExistingWorkAndRejectsLateDataBeforeDelayedTick"),
    "M07": ("OnlineSync", "PlatformLifecycleTest", "queuedDispatchAtBackgroundEntryIsRevokedAndForegroundStartsPromptly"),
    "M08": ("LocalClient", "PlatformLifecycleTest", "graceCannotReconnectOrStartAfterIdentify"),
    "M09": ("LocalClient", "LocalRuntimeTest", "identifyRejectsOldSessionsIncludingSameKeyAndABA"),
    "M10": ("OnlineSync", "OnlineSyncTest", "identifySameKeyAndABARacesRejectOldDataErrorsAndTimers"),
    "M11": ("Events", "EventsTest", "callbackReentryCanIdentifyTrackAndFlushWithoutChangingPriorCoverage"),
    "M12": ("LocalClient", "LocalRuntimeTest", "bootstrapShadowArchiveAndFullReplacementFollowSpec"),
    "M13": ("LocalClient", "OnlineSyncTest", "streamingHandshakeDoesNotConfirmAndEqualTimestampPatchesRemainOrdered"),
    "M14": ("SyncProtocol", "SyncProtocolTest", "invalidRecordsSkipIndependentlyButDuplicateKeysRejectEnvelope"),
    "M15": ("OnlineSync", "OnlineSyncTest", "readinessTimeoutDoesNotStopRecoveryAndTerminalRejectionCannotBeReset"),
    "M16": ("Events", "EventsTest", "eventGroupAndPayloadBoundsRejectNewWorkWithoutUnboundedTasks"),
    "M17": ("Events", "EventsTest", "evaluationRequiresRemoteConfirmationValidMetadataAndSuccessfulConversion"),
    "M18": ("Events", "EventsTest", "distinctGroupsAndUsersAreNeverDeduplicatedAcrossFlush"),
    "M19": ("Events", "EventsTest", "transitionBudgetExcludesNewBackgroundEventsAndLateAcknowledgement"),
    "M20": ("Execution", "LocalRuntimeTest", "callbackReentryFailureAndIndependentDetach"),
    "M21": ("LocalClient", "LocalRuntimeTest", "saturatedWaitsAndBlockedCallbacksCannotStarveClose"),
    "M22": ("Execution", "LocalRuntimeTest", "statusSanitizesExternalDiagnosticsAndTerminalCannotRestart"),
    "M23": ("OnlineSync", "PlatformLifecycleTest", "backgroundCreationAndIdentifyUsePollingWithoutStreamingGrace"),
    "M24": ("Execution", "LocalRuntimeTest", "elapsedDeadlinesSurviveWallClockRollbackAndLateConfirmation"),
    "M25": ("Events", "EventsTest", "backgroundAndForegroundSealSeparateGroupsAndMissedTicksDoNotSplit"),
    "M26": ("Events", "EventsTest", "terminalDeliveryFinalizesAllAndCannotBeResetButFlagsStillWork"),
    "M27": ("Events", "EventsTest", "transitionBudgetExcludesNewBackgroundEventsAndLateAcknowledgement"),
    "M28": ("Events", "EventsTest", "terminalDeliveryFinalizesAllAndCannotBeResetButFlagsStillWork"),
    "M29": ("OnlineSync", "OnlineSyncTest", "backgroundPollingAndGraceRestoreStreamingWithoutOverlap"),
    "M30": ("Events", "PlatformLifecycleTest", "platformWithdrawalEndsTransitionBudgetWithoutRegrantOnIdleExit"),
    "P01": ("Persistence", "PersistenceTest", "completeContextAndNamespaceIsolation"),
    "P02": ("Persistence", "PersistenceTest", "lruCorruptionAndStorageFailure"),
    "P03": ("Persistence", "PersistenceTest", "snapshotAboveFormerByteLimitPersistsAndRestoresWithoutEvictingOtherContexts"),
    "P04": ("Persistence", "PersistenceTest", "clearDuringPhysicalWriteErasesAdmittedOldWork"),
    "P05": ("Persistence", "PersistenceTest", "anonymousCreationRestartResetFailureAndRevisionFencing"),
    "P07": ("Persistence", "PersistenceTest", "lateCacheAndMissCannotReplaceRemoteIdentifyOrClose"),
    "P10": ("Persistence", "PersistenceTest", "logicalCommitOrderCoalescesAcrossClientsIncludingEqualTimestamps"),
    "A01": ("RuntimeFactory", "ReleaseAcceptanceTest", "disabledOrConflictingAttributesNeverSampleAndUnavailableFieldsAreOmitted"),
    "A02": ("RuntimeFactory", "ReleaseAcceptanceTest", "identifyResamplesButForegroundDoesNotAndOldSnapshotsStayUnchanged"),
}
CORE = {
    "Configuration": ("RuntimeFactory", "co.featbit.android.api.ModelTest"),
    "Offline": ("LocalClient", "LocalRuntimeTest"),
    "Identity": ("LocalClient", "LocalRuntimeTest"),
    "User payload": ("SyncProtocol", "SyncProtocolTest"),
    "Initialization": ("OnlineSync", "OnlineSyncTest"),
    "WebSocket": ("SyncTransport", "SyncTransportTest"),
    "Polling": ("OnlineSync", "OnlineSyncTest"),
    "Polling fallback": ("OnlineSync", "OnlineSyncTest"),
    "Invalid data": ("SyncProtocol", "SyncProtocolTest"),
    "Storage": ("LocalClient", "LocalRuntimeTest"),
    "Cache": ("Persistence", "PersistenceTest"),
    "Evaluation": ("Values", "LocalRuntimeTest"),
    "Events": ("EventProtocol", "EventsTest"),
    "Deduplication and delivery": ("Events", "EventsTest"),
    "Flush and close": ("Events", "EventsTest"),
    "Notifications and diagnostics": ("Execution", "LocalRuntimeTest"),
}


def render(evidence):
    cases = {}
    for file in (evidence / "sdk-test-results").glob("TEST-*.xml"):
        for case in ET.parse(file).getroot().findall("testcase"):
            status = "failed" if case.find("failure") is not None or case.find("error") is not None else "skipped" if case.find("skipped") is not None else "passed"
            cases[(case.attrib["classname"], case.attrib["name"])] = status
    lines = ["# Requirement evidence", "", "Representative executed checks only. A passed check is not a full requirement pass.",
             "Physical-device, deployed storage/MQ, automatic-attribute and exhaustive diagnostics gates remain open; see docs/conformance.md.", "",
             "| Requirement | Implementation (.kt, internal package) | Test | Result |", "| --- | --- | --- | --- |"]
    missing = []
    for requirement, (implementation, suite, method) in MOBILE.items():
        status = cases.get(("co.featbit.android.internal." + suite, method), "not executed")
        if status != "passed":
            missing.append(requirement)
        lines.append(f"| {requirement} | {implementation} | {suite}.{method} | {status} |")
    for requirement, (implementation, suite) in CORE.items():
        full = suite if "." in suite else "co.featbit.android.internal." + suite
        statuses = [v for (cls, _), v in cases.items() if cls == full]
        result = f"{len(statuses)} checks passed" if statuses and all(v == "passed" for v in statuses) else "incomplete/failed"
        if result == "incomplete/failed":
            missing.append("Core: " + requirement)
        lines.append(f"| Core: {requirement} | {implementation} | {suite} | {result} |")
    for requirement in ("P06", "P08", "P09"):
        lines.append(f"| {requirement} | Persistent events unsupported | N/A | not a pass |")
    lines.extend(["| Android | AndroidPlatformMonitor | See per-row platform logs and report.json | emulator evidence only if executed |",
                  "| iOS | Unsupported | N/A | not a pass |", ""])
    (evidence / "conformance.md").write_text("\n".join(lines), encoding="utf-8")
    return missing


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    missing = render(args.evidence)
    print(json.dumps({"missing_or_failed_representative_checks": missing}))
    raise SystemExit(bool(missing))
