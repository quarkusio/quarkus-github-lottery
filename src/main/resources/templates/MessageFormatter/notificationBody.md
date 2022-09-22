Hey @{report.username}, here's your report for {report.repositoryName} on {report.localDate}.
{#let bucket=report.triage}
# Triage
{#if bucket.issues.isEmpty}
No issues in this category this time.
{#else}
{#for issue in bucket.issues}
 - {issue.url}
{/for}
{/if}
{/let}
