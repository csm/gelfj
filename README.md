GELFJ - A GELF Appender for Log4j and a GELF Handler for JDK Logging
====================================================================

Changes!
--------

I've changed the following from [upstream](https://github.com/t0xa/gelfj):

* Use `SocketChannel` in favor of `Socket` and `OutputStream` to send messages via TCP. Fail if sending the entire message fails.
* Use `InetAddress.getAllByName` and round-robin through the answers (ELBs in AWS can have multiple IP addresses).
* Look up the hostname again after 60 seconds (AWS recommends a 60 second TTL for host lookups). Note that Java may cache DNS lookups like an idiot; check your installation.
* Retry sending a message once, since we may need to reconnect.
* Spew a little more debug output to stderr, but not too much.
* Use a queue for TCP messages, because it's stupid to have a blocking network write in your logging path.
    * Note a more recent commit tries out a bounded priority queue, which should prioritize messages with a higher severity (which means a lower integer log level). This priority queue implementation isn't well tested yet.

I did some investigation into various queue implementations:

* Just using `LinkedBlockingQueue` works great, especially in the many-producers-one-consumer case, but we really want to prioritize more severe messages.
* If we add an upper bound to `PriorityBlockingQueue`, we actually get a *lot* of items rejected, especially as the number of producers increases.
* `ConcurrentSkipListSet` lends itself well to this, though it is an approximation (you might think you've enqueued an item when it was actually dropped, or you might drop more messages than you need to). This is the implementation I'm trying, though again, it isn't well tested yet.
* Guava's `MinMaxPriorityQueue` works well too, but it requires explicit locking. Since I have many, many writers, I don't really want them all bogged down contending for a single lock, even if that lock is only used very, very briefly.

Downloading
-----------

Add the following dependency section to your pom.xml:

    <dependencies>
      ...
      <dependency>
        <groupId>org.graylog2</groupId>
        <artifactId>gelfj</artifactId>
        <version>1.1.7</version>
        <scope>compile</scope>
      </dependency>
      ...
    </dependencies>

What is GELFJ
-------------

It's very simple GELF implementation in pure Java with the Log4j appender and JDK Logging Handler. It supports chunked messages which allows you to send large log messages (stacktraces, environment variables, additional fields, etc.) to a [Graylog2](http://www.graylog2.org/) server.

Following transports are supported:

 * TCP
 * UDP
 * AMQP


How to use GELFJ
----------------

Drop the latest JAR into your classpath and configure Log4j to use it.

Log4j appender
--------------

GelfAppender will use the log message as a short message and a stacktrace (if exception available) as a long message if "extractStacktrace" is true.

To use GELF Facility as appender in Log4j (XML configuration format):

    <appender name="graylog2" class="org.graylog2.log.GelfAppender">
        <param name="graylogHost" value="192.168.0.201"/>
        <param name="originHost" value="my.machine.example.com"/>
        <param name="extractStacktrace" value="true"/>
        <param name="addExtendedInformation" value="true"/>
        <param name="facility" value="gelf-java"/>
        <param name="Threshold" value="INFO"/>
        <param name="additionalFields" value="{'environment': 'DEV', 'application': 'MyAPP'}"/>
    </appender>

and then add it as a one of appenders:

    <root>
        <priority value="INFO"/>
        <appender-ref ref="graylog2"/>
    </root>

Or, in the log4j.properties format:

    # Define the graylog2 destination
    log4j.appender.graylog2=org.graylog2.log.GelfAppender
    log4j.appender.graylog2.graylogHost=graylog2.example.com
    log4j.appender.graylog2.originHost=my.machine.example.com
    log4j.appender.graylog2.facility=gelf-java
    log4j.appender.graylog2.layout=org.apache.log4j.PatternLayout
    log4j.appender.graylog2.extractStacktrace=true
    log4j.appender.graylog2.addExtendedInformation=true
    log4j.appender.graylog2.additionalFields={'environment': 'DEV', 'application': 'MyAPP'}

    # Send all INFO logs to graylog2
    log4j.rootLogger=INFO, graylog2

AMQP Configuration:

    log4j.appender.graylog2=org.graylog2.log.GelfAppender
    log4j.appender.graylog2.amqpURI=amqp://amqp.address.com
    log4j.appender.graylog2.amqpExchangeName=messages
    log4j.appender.graylog2.amqpRoutingKey=gelfudp
    log4j.appender.graylog2.amqpMaxRetries=5
    log4j.appender.graylog2.facility=test-application
    log4j.appender.graylog2.layout=org.apache.log4j.PatternLayout
    log4j.appender.graylog2.layout.ConversionPattern=%d{HH:mm:ss,SSS} %-5p [%t] [%c{1}] - %m%n
    log4j.appender.graylog2.additionalFields={'environment': 'DEV', 'application': 'MyAPP'}
    log4j.appender.graylog2.extractStacktrace=true
    log4j.appender.graylog2.addExtendedInformation=true

Options
-------

GelfAppender supports the following options:

- **graylogHost**: Graylog2 server where it will send the GELF messages; to use UDP instead of TCP, prefix with `udp:`
- **graylogPort**: Port on which the Graylog2 server is listening; default 12201 (*optional*)
- **originHost**: Name of the originating host; defaults to the local hostname (*optional*)
- **extractStacktrace** (true/false): Add stacktraces to the GELF message; default false (*optional*)
- **addExtendedInformation** (true/false): Add extended information like Log4j's NDC/MDC; default false (*optional*)
- **includeLocation** (true/false): Include caller file name and line number. Log4j documentation warns that generating caller location information is extremely slow and should be avoided unless execution speed is not an issue; default true (*optional*)
- **facility**: Facility which to use in the GELF message; default "gelf-java"
- **amqpURI**: AMQP URI (*required when using AMQP integration*)
- **amqpExchangeName**: AMQP Exchange name - should be the same as setup in graylog2-radio (*required when using AMQP integration*)
- **amqpRoutingKey**: AMQP Routing key - should be the same as setup in graylog2-radio (*required when using AMQP integration*)
- **amqpMaxRetries**: Retries count; default value 0 (*optional*)

Logging Handler
---------------

Configured via properties as a standard Handler like

    handlers = org.graylog2.logging.GelfHandler

    .level = ALL

    org.graylog2.logging.GelfHandler.level = ALL
    org.graylog2.logging.GelfHandler.graylogHost = syslog.example.com
    #org.graylog2.logging.GelfHandler.graylogPort = 12201
    #org.graylog2.logging.GelfHandler.extractStacktrace = true
    #org.graylog2.logging.GelfHandler.additionalField.0 = foo=bah
    #org.graylog2.logging.GelfHandler.additionalField.1 = foo2=bah2
    #org.graylog2.logging.GelfHandler.facility = local0

    .handlers=org.graylog2.logging.GelfHandler

What is GELF
------------

The Graylog Extended Log Format (GELF) avoids the shortcomings of classic plain syslog:

- Limited to length of 1024 byte
- Not much space for payloads like stacktraces
- Unstructured. You can only build a long message string and define priority, severity etc.

You can get more information here: [http://www.graylog2.org/about/gelf](http://www.graylog2.org/about/gelf)
