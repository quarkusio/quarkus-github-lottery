Hey @{report.username}, here's your report for {report.repositoryName} on {report.localDate}.
{#if !report.config.maintenanceLabels.isEmpty}

Maintenance areas: {report.config.maintenanceLabels.asMarkdownLabel}.
{/if}

{#if report.triage.present}
# Triage
{#include MessageFormatter/notificationBodyBucketContent bucket=report.triage.get()}

<sup>Issues that haven't been assigned an area yet. Please add an area label, remove the {report.config.triageLabel.asMarkdownLabel} label, optionally ping maintainers.</sup>

{/include}

{/if}
{#if report.feedbackNeeded.present}
# Feedback needed
{#include MessageFormatter/notificationBodyBucketContent bucket=report.feedbackNeeded.get()}

<sup>Issues with missing reproducer/information. Please ping the reporter, or close the issue if it's taking too long.</sup>

{/include}

{/if}
{#if report.feedbackProvided.present}
# Feedback provided
{#include MessageFormatter/notificationBodyBucketContent bucket=report.feedbackProvided.get()}

<sup>Issues with newly provided reproducer/information. Please have a closer look, possibly remove the {report.config.feedbackLabels.asMarkdownLabel} label, and plan further work.</sup>

{/include}

{/if}
{#if report.stale.present}
# Stale
{#include MessageFormatter/notificationBodyBucketContent bucket=report.stale.get()}

<sup>Issues last updated a long time ago. Please have a closer look, re-prioritize, ping someone, label as "on ice", close the issue, ...</sup>

{/include}

{/if}
{#if report.stewardship.present}
# Stewardship
{#include MessageFormatter/notificationBodyBucketContent bucket=report.stewardship.get()}

<sup>Issues across all areas last updated a long time ago. Please have a closer look, re-prioritize, ping someone, label as "on ice", close the issue, ...</sup>

{/include}

{/if}
---
<sup>If you no longer want to receive these notifications, just close [any issue assigned to you in the notification repository](https://github.com/{notificationRepositoryName}/issues/assigned/@me). Reopening the issue will resume the notifications.</sup>
