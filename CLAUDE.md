# Grouper MCP training workspace

## Grouper source code
- `grouper/` is the Grouper v7 source (release 7.9.0, from Internet2/grouper).
- Use it to answer questions about how Grouper works, GSH syntax, and config.
- Config defaults are in the `*.base.properties` files, e.g. `grouper/grouper/conf/grouper.base.properties` and `grouper/grouper/conf/grouper-loader.base.properties`. Others in the same folder: `grouper.hibernate`, `grouper-ws-ng`, `grouper-ui-ng`, `grouper.cache`, `subject`; the client's are in `grouper/grouper-misc/grouperClient/conf/`.
- For any config question, or any answer that depends on a config setting, check the `*.base.properties` files first. They hold the defaults and the comments that explain each setting. Only then look at Java code or the wiki. Name the property and file in the answer, and say that the GTE may override the default in its own `.properties` files or in database config.
- The Grouper wiki is mirrored in `grouper/grouper-misc/grouper-docs/wikiMirror`.
- Jira issues are mirrored in `grouper/grouper-misc/grouper-docs/issueMirror`.
- Check the source before you answer, and say which file you used.
