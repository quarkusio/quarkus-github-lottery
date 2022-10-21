Hey @{report.username}, here's your report for {report.repositoryName} on {report.localDate}.

{#if report.triage.present}
# Triage
{#include MessageFormatter/notificationBodyBucketContent bucket=report.triage.get() /}
{/if}
{#if report.reproducerNeeded.present}
# Reproducer needed
{#include MessageFormatter/notificationBodyBucketContent bucket=report.reproducerNeeded.get() /}
{/if}
{#if report.reproducerProvided.present}
# Reproducer provided
{#include MessageFormatter/notificationBodyBucketContent bucket=report.reproducerProvided.get() /}
{/if}
{#if report.stale.present}
# Stale
{#include MessageFormatter/notificationBodyBucketContent bucket=report.stale.get() /}
{/if}

---
<sup>If you no longer want to receive these notifications, send a pull request to the GitHub repository `{report.repositoryName}` to remove the section relative to your username from the file `{github:configPath}`.</sup>
