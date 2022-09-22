Here are the reports for {drawRef.repositoryName} on {drawRef.instant}.

{#for report in reports}
# {report.username}
{#let bucket=report.triage}
## Triage
{#for issueNumber in bucket.issueNumbers}
 - {drawRef.repositoryRef.repositoryName}#{issueNumber}
{/for}
{/let}

{/for}
{payload}
