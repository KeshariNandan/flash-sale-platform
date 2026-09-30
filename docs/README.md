# Design notes and report material

Working notes that feed the Woolf project report. Each item is written the same
day the corresponding code lands, so the final report is assembly rather than
recall.

Planned contents:

- `requirements.md` - functional / non-functional requirements, users, use cases, feature table
- `schema.md` - tables, keys, foreign keys, cardinality (text form for the report)
- `claim-feature.md` - request payload, controller -> service -> Redis -> outbox -> Kafka -> worker flow
- `benchmark.md` - baseline vs optimized numbers from `load-test/results/`
- `deployment-aws.md` - VPC, security groups, Elastic Beanstalk, RDS, ElastiCache, MSK
- `diagrams/` - draw.io sources and exported PNGs (class diagram, ER diagram, deployment)
- `references.md` - every external source consulted, with access date/time, author, title
