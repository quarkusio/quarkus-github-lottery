Hey @{report.username}, here's your report for {report.repositoryName} on {report.localDate}.

{#if report.triage.present}
# Triage
{#include MessageFormatter/notificationBodyBucketContent bucket=report.triage.get() /}
{/if}
{#if report.feedbackNeeded.present}
# Feedback needed
{#include MessageFormatter/notificationBodyBucketContent bucket=report.feedbackNeeded.get() /}
{/if}
{#if report.feedbackProvided.present}
# Feedback provided
{#include MessageFormatter/notificationBodyBucketContent bucket=report.feedbackProvided.get() /}
{/if}
{#if report.stale.present}
# Stale
{#include MessageFormatter/notificationBodyBucketContent bucket=report.stale.get() /}
{/if}
{#if report.stewardship.present}
# Stewardship
{#include MessageFormatter/notificationBodyBucketContent bucket=report.stewardship.get() /}
{/if}

---
<sup>If you no longer want to receive these notifications, just close [any issue assigned to you in the notification repository](https://github.com/{notificationRepositoryName}/issues/assigned/@me). Reopening the issue will resume the notifications.</sup>
