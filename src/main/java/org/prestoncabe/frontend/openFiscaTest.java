package org.prestoncabe.frontend;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.inject.Inject;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;

@Path("/openFiscaTestForm")
public class openFiscaTest {

    @Inject
    Template openFiscaTest; // Inject the Qute template

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance get() {
        return openFiscaTest.instance(); // Render the template
    }
}
