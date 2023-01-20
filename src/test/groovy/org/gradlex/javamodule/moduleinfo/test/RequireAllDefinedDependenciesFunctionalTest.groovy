package org.gradlex.javamodule.moduleinfo.test

import org.gradle.testkit.runner.TaskOutcome
import org.gradlex.javamodule.moduleinfo.test.fixture.GradleBuild
import org.gradlex.javamodule.moduleinfo.test.fixture.LegacyLibraries
import spock.lang.Specification

class RequireAllDefinedDependenciesFunctionalTest extends Specification {

    @Delegate
    GradleBuild build = new GradleBuild()

    LegacyLibraries libs = new LegacyLibraries(false)

    def setup() {
        settingsFile << 'rootProject.name = "test-project"'
        buildFile << '''
            plugins {
                id("application")
                id("org.gradlex.extra-java-module-info")
            }
            application {
                mainModule.set("org.gradle.sample.app")
                mainClass.set("org.gradle.sample.app.Main")
            }
        '''
    }

    def "can automatically add requires directives based on component metadata"() {
        given:
        file("src/main/java/org/gradle/sample/app/Main.java") << """
            package org.gradle.sample.app;
            
            import org.apache.http.NameValuePair;
            import org.apache.http.client.methods.HttpPost;
            import org.apache.http.client.entity.UrlEncodedFormEntity;
            import org.apache.http.message.BasicNameValuePair;
            import java.util.List;
            import java.util.ArrayList;

            public class Main {
                public static void main(String[] args) throws Exception {
                    HttpPost httpPost = new HttpPost("http://targethost/login");
                    List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                    nvps.add(new BasicNameValuePair("username", "vip"));
                    nvps.add(new BasicNameValuePair("password", "secret"));
                    httpPost.setEntity(new UrlEncodedFormEntity(nvps));
                }
            }
        """
        file("src/main/java/module-info.java") << """
            module org.gradle.sample.app {
                exports org.gradle.sample.app;
                
                requires org.apache.httpcomponents.httpclient;
            }
        """
        buildFile << """          
            dependencies {
                implementation("org.apache.httpcomponents:httpclient:4.5.14")
            }
            
            extraJavaModuleInfo {
                module("${libs.commonsHttpClient}", "org.apache.httpcomponents.httpclient") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                module("${libs.commonsLogging}", "org.apache.commons.logging") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                knownModule("commons-codec:commons-codec", "org.apache.commons.codec")
                knownModule("org.apache.httpcomponents:httpcore", "org.apache.httpcomponents.httpcore")
            }
        """

        expect:
        run().task(':run').outcome == TaskOutcome.SUCCESS
    }

    def "can automatically add requires directives based on component metadata when module is only used in test"() {
        given:
        file("src/test/java/org/gradle/sample/app/test/AppTest.java") << """
            package org.gradle.sample.app.test;
            
            import org.apache.http.NameValuePair;
            import org.apache.http.client.methods.HttpPost;
            import org.apache.http.client.entity.UrlEncodedFormEntity;
            import org.apache.http.message.BasicNameValuePair;
            import org.junit.Test;
            import java.util.List;
            import java.util.ArrayList;

            public class AppTest {
                
                @Test
                public void testPost() throws Exception {
                    HttpPost httpPost = new HttpPost("http://targethost/login");
                    List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                    nvps.add(new BasicNameValuePair("username", "vip"));
                    nvps.add(new BasicNameValuePair("password", "secret"));
                    httpPost.setEntity(new UrlEncodedFormEntity(nvps));
                }
            }
        """
        file("src/test/java/module-info.java") << """
            open module org.gradle.sample.app.test {
                requires junit;
                requires org.apache.httpcomponents.httpclient;
            }
        """
        buildFile << """          
            dependencies {
                testImplementation("junit:junit:4.13.2")
                testImplementation("org.apache.httpcomponents:httpclient:4.5.14")
            }
            
            extraJavaModuleInfo {
                automaticModule("org.hamcrest:hamcrest-core", "org.hamcrest.core")
                module("${libs.commonsHttpClient}", "org.apache.httpcomponents.httpclient") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                module("${libs.commonsLogging}", "org.apache.commons.logging") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                knownModule("commons-codec:commons-codec", "org.apache.commons.codec")
                knownModule("org.apache.httpcomponents:httpcore", "org.apache.httpcomponents.httpcore")
            }
        """

        expect:
        test().task(':test').outcome == TaskOutcome.SUCCESS
    }

    def "gives error if dependencies cannot be discovered"() {
        given:
        file("src/main/java/org/gradle/sample/app/Main.java") << """
            package org.gradle.sample.app;
            public class Main {
                public static void main(String[] args) { }
            }
        """
        file("src/main/java/module-info.java") << """
            module org.gradle.sample.app {
                exports org.gradle.sample.app;
                
                requires org.apache.httpcomponents.httpclient;
            }
        """
        buildFile << """          
            dependencies {
                implementation("org.apache.httpcomponents:httpclient:4.5.14")
            }
            
            extraJavaModuleInfo {
                module("${new LegacyLibraries(true).commonsHttpClient}", "org.apache.httpcomponents.httpclient") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                module("${libs.commonsLogging}", "org.apache.commons.logging") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                knownModule("commons-codec:commons-codec", "org.apache.commons.codec")
                knownModule("org.apache.httpcomponents:httpcore", "org.apache.httpcomponents.httpcore")
            }
        """

        expect:
        fail().output.contains("[requires directives from metadata] " +
                "Cannot find dependencies for 'org.apache.httpcomponents.httpclient'. " +
                "Are 'httpclient-4.5.14.jar' the correct component coordinates?")
    }

    def "gives error if the module name for certain ga coordinates is not known"() {
        given:
        file("src/main/java/org/gradle/sample/app/Main.java") << """
            package org.gradle.sample.app;
            public class Main {
                public static void main(String[] args) { }
            }
        """
        file("src/main/java/module-info.java") << """
            module org.gradle.sample.app {
                exports org.gradle.sample.app;
                
                requires org.apache.httpcomponents.httpclient;
            }
        """
        buildFile << """          
            dependencies {
                implementation("org.apache.httpcomponents:httpclient:4.5.14")
            }
            
            extraJavaModuleInfo {
                module("${libs.commonsHttpClient}", "org.apache.httpcomponents.httpclient") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                module("${libs.commonsLogging}", "org.apache.commons.logging") {
                    exportAllPackages()
                    requireAllDefinedDependencies()
                }
                knownModule("commons-codec:commons-codec", "org.apache.commons.codec")
            }
        """

        expect:
        fail().output.contains("[requires directives from metadata] " +
                "The module name of the following component is not known: org.apache.httpcomponents:httpcore")
    }
}
