package net.wasdev.gameon.room;

import java.util.logging.Level;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("health")
public class RoomHealth {

    /**
     * CDI injection of Connection Utilities (consistent send/receive/error
     * handling)
     */
    @Inject
    ConnectionUtils connectionUtils;

    /**
     * The concierge URL injected from JNDI via CDI.
     * 
     * @see {@code conciergeUrl} in
     *      {@code /room-wlpcfg/servers/gameon-room/server.xml}
     */
    @Resource(lookup = "conciergeLocation")
    String conciergeLocation;

    /**
     * The base service URL injected from JNDI via CDI.
     * 
     * @see {@code thisLocation} in
     *      {@code /room-wlpcfg/servers/gameon-room/server.xml}
     */
    @Resource(lookup = "thisLocation")
    String thisLocation;

    /**
     * The registrationSecret injected from JNDI via CDI.
     * 
     * @see {@code registrationSecret} in
     *      {@code /room-wlpcfg/servers/gameon-room/server.xml}
     */
    @Resource(lookup = "registrationSecret")
    String registrationSecret;
    
    private boolean allIsWell = true;
    
    @PostConstruct
    private void postConstructCheck() {
        if (conciergeLocation == null) {
            Log.log(Level.SEVERE, this, "service_concierge was not defined in the environment.");
            allIsWell = false;
        }

        if (thisLocation == null) {
            Log.log(Level.SEVERE, this, "service_room (referencing this service) was not defined in the environment");
            allIsWell = false;
        }

        if (registrationSecret == null) {
            Log.log(Level.SEVERE, this, "registrationSecret was not found, check server.xml/server.env");
            allIsWell = false;
        }
    }

    
    /**
     * GET /rooms/v1/health
     */
    @GET
    public Response healthCheck() {
        if ( allIsWell ) {
            return Response.ok("All is well").build();
        } else {
            // Things are NOT ok. Something didn't get initialized correctly.
            return Response.serverError().build();
        }
    }


}
