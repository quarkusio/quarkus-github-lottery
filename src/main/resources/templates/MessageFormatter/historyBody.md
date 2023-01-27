Here are the reports for {drawRef.repositoryName} on {drawRef.instant}.

{#for report in reports}
# {report.username}
{#if report.triage.present}
## Triage
{#include MessageFormatter/historyBodyBucketContent bucket=report.triage.get() /}
{/if}
{#if report.feedbackNeeded.present}
## Feedback needed
{#include MessageFormatter/historyBodyBucketContent bucket=report.feedbackNeeded.get() /}
{/if}
{#if report.feedbackProvided.present}
## Feedback provided
{#include MessageFormatter/historyBodyBucketContent bucket=report.feedbackProvided.get() /}
{/if}
{#if report.stale.present}
## Stale
{#include MessageFormatter/historyBodyBucketContent bucket=report.stale.get() /}
{/if}
{#if report.stewardship.present}
## Stewardship
{#include MessageFormatter/historyBodyBucketContent bucket=report.stewardship.get() /}
{/if}

{/for}
{payload}
