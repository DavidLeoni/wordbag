<p class="josman-to-strip">
WARNING: WORK IN PROGRESS - THIS IS ONLY A TEMPLATE FOR THE DOCUMENTATION. <br/>
RELEASE DOCS ARE ON THE <a href="http://diversicon-kb.eu/manual/diversicon-core" target="_blank">PROJECT WEBSITE</a>
</p>


This release allows to [perform importing](Operations.md#xml-import) of XML [lexical resources](DiversiconLMF.md), with [some validation](Operations.md#xml-validation) for checking namespaces.

 <!--If you are upgrading from previous version, see [Release notes](CHANGES.md).-->

### Getting started

If you use Maven as build system, put in the `dependencies` section of your `pom.xml`:

```xml
    <dependency>
        <groupId>eu.kidf</groupId>
        <artifactId>diversicon-core</artifactId>
        <version>${project.version}</version>
    </dependency>
```


If you want to use Wordnet 3.1,  add this additional dependency:

```xml
	<dependency>
		<groupId>eu.kidf</groupId>
		<artifactId>div-wn31-h2db</artifactId>
		<version>${project.version}</version>		
	</dependency>
```

In case updates are available, version numbers follow <a href="http://semver.org/" target="_blank">semantic versioning</a> rules.


### Example Usage

To create a database with Wordnet 3.1 and then import the example resource Smartphones, you can
look at the example [`TestApp1`](../../src/test/java/eu/kidf/diversicon/core/test/TestApp1.java) 

For more usage examples, you can have a look at DiverCLI project, in particular the various [Commands](https://github.com/diversicon-kb/divercli/tree/master/src/main/java/eu/kidf/diversicon/cli/commands)

### Logging

Diversicon uses <a href="http://www.slf4j.org" target="_blank">SLF4J </a> logging system. Library has only `slf4j-api` as dependency. For using logging during development / testing, see [the wiki](../../../wiki/#logging).