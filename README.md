# Charon

Console utility for exporting spaces from Confluence implemented in Clojure.

![Clojure CI](https://github.com/shapiy/charon/workflows/Clojure%20CI/badge.svg)

## Usage

```shell
java --jar charon.jar  
  -o, --output OUTPUT        Output directory
      --space-url SPACE_URL  Confluence space URL
      --page-url PAGE_URL    Confluence page URL
  -u, --username USERNAME    Username
  -p, --password PASSWORD    Password
  -d, --debug
  -h, --help
```

## Exported content

Charon exports three types of content from Confluence:

- Pages
- Attachments
- Table of Contents

A sample file structure created in the output directory:

```
publish/
├── files
│   ├── attachment.zip
│   └── image.jpg
├── toc.xml
└── welcome.html
```

### Pages
You can select the exported content using `--space-url` or `--page-url` option. If `--space-url` is specified, all pages in the space are saved. For `--page-url`, only the page and its descendents are saved.

### Attachments

### Table of Contents
A nested Table of Contents is automatically generated based on the list of exported content.
```xml
<toc>
  <nav href="home.html" title="Home">
    <nav href="welcome.html" title="Welcome"></nav>
  </nav>
</toc>
```
