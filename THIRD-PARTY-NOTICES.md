# Third-Party Notices

Dediren distribution archives include the root `LICENSE` file for Dediren's own
MIT-licensed source and binaries. They also redistribute third-party Java
runtime libraries under `runtimes/elk-layout-java/lib/` for the ELK Java helper.

The upstream JAR files retain their embedded `META-INF` license, notice, Maven
POM, manifest, or Eclipse `about.html` resources. This file is the bundle-level
summary for those redistributed runtime libraries.

## ELK Java Helper Runtime

| Component | Version | Notice source in bundled JAR | License summary |
| --- | --- | --- | --- |
| `checker-qual` | 3.33.0 | `META-INF/LICENSE.txt`, manifest `Bundle-License` | MIT License |
| `error_prone_annotations` | 2.18.0 | Maven POM `licenses` | Apache License 2.0 |
| `failureaccess` | 1.0.1 | manifest `Bundle-License` | Apache License 2.0 |
| `guava` | 32.1.2-jre | `META-INF/LICENSE`, manifest `Bundle-License` | Apache License 2.0 |
| `listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | Maven POM metadata; empty Guava placeholder artifact | Apache License 2.0 |
| `jackson-annotations` | 2.17.3 | `META-INF/LICENSE`, `META-INF/NOTICE`, manifest `Bundle-License` | Apache License 2.0 |
| `jackson-core` | 2.17.3 | `META-INF/LICENSE`, `META-INF/NOTICE`, manifest `Bundle-License` | Apache License 2.0 |
| `jackson-databind` | 2.17.3 | `META-INF/LICENSE`, `META-INF/NOTICE`, manifest `Bundle-License` | Apache License 2.0 |
| `jsr305` | 3.0.2 | Maven POM `licenses`, manifest `Bundle-License` | Apache License 2.0 |
| `org.eclipse.elk.alg.common` | 0.11.0 | `about.html`, manifest `Bundle-License` | Eclipse Public License 2.0 |
| `org.eclipse.elk.alg.layered` | 0.11.0 | `about.html`, manifest `Bundle-License` | Eclipse Public License 2.0 |
| `org.eclipse.elk.core` | 0.11.0 | `about.html`, manifest `Bundle-License` | Eclipse Public License 2.0 |
| `org.eclipse.elk.graph` | 0.11.0 | `about.html`, manifest `Bundle-License` | Eclipse Public License 2.0 |
| `org.eclipse.emf.common` | 2.12.0 | `about.html`, Maven POM `licenses` | Eclipse Public License 1.0 |
| `org.eclipse.emf.ecore` | 2.12.0 | `about.html`, Maven POM `licenses` | Eclipse Public License 1.0 |
| `org.eclipse.emf.ecore.xmi` | 2.12.0 | `about.html`, Maven POM `licenses` | Eclipse Public License 1.0 |
| `org.eclipse.xtext.xbase.lib` | 2.32.0 | manifest `Bundle-License` | Eclipse Public License 2.0 |

The Dediren project does not grant additional rights in these third-party
components. Review the embedded upstream notices before redistributing a
modified bundle.
