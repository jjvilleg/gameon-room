/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.room;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpointConfig;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import net.wasdev.gameon.room.engine.Engine;
import net.wasdev.gameon.room.engine.Room;
import net.wasdev.gameon.room.engine.meta.ExitDesc;

/**
 * Manages the registration of all rooms in the Engine with the concierge
 */
@ApplicationScoped
public class LifecycleManager implements ServerApplicationConfig {
    private String conciergeLocation = null;
    private String registrationSecret = null;
    private String thisLocation = null;

    Engine e = Engine.getEngine();

    public static class SessionRoomResponseProcessor
            implements net.wasdev.gameon.room.engine.Room.RoomResponseProcessor {
        AtomicInteger counter = new AtomicInteger(0);

        private void generateEvent(Session session, JsonObject content, String userID, boolean selfOnly, int bookmark)
                throws IOException {
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add("type", "event");
            response.add("content", content);
            response.add("bookmark", bookmark);

            String msg = "player," + (selfOnly ? userID : "*") + "," + response.build().toString();
            System.out.println("ROOM(PE): sending to session " + session.getId() + " message:" + msg);
            session.getBasicRemote().sendText(msg);
        }

        @Override
        public void playerEvent(String senderId, String selfMessage, String othersMessage) {
            // System.out.println("Player message :: from("+senderId+")
            // onlyForSelf("+String.valueOf(selfMessage)+")
            // others("+String.valueOf(othersMessage)+")");
            JsonObjectBuilder content = Json.createObjectBuilder();
            boolean selfOnly = true;
            if (othersMessage != null && othersMessage.length() > 0) {
                content.add("*", othersMessage);
                selfOnly = false;
            }
            if (selfMessage != null && selfMessage.length() > 0) {
                content.add(senderId, selfMessage);
            }
            JsonObject json = content.build();
            int count = counter.incrementAndGet();
            for (Session s : activeSessions) {
                try {
                    generateEvent(s, json, senderId, selfOnly, count);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        private void generateRoomEvent(Session session, JsonObject content, int bookmark) throws IOException {
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add("type", "event");
            response.add("content", content);
            response.add("bookmark", bookmark);

            String msg = "player,*," + response.build().toString();
            System.out.println("ROOM(RE): sending to session " + session.getId() + " message:" + msg);

            session.getBasicRemote().sendText(msg);
        }

        @Override
        public void roomEvent(String s) {
            // System.out.println("Message sent to everyone :: "+s);
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("*", s);
            JsonObject json = content.build();
            int count = counter.incrementAndGet();
            for (Session session : activeSessions) {
                try {
                    generateRoomEvent(session, json, count);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        public void chatEvent(String username, String msg) {
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("type", "chat");
            content.add("username", username);
            content.add("content", msg);
            content.add("bookmark", counter.incrementAndGet());
            JsonObject json = content.build();
            for (Session session : activeSessions) {
                try {
                    String cmsg = "player,*," + json.toString();
                    System.out.println("ROOM(CE): sending to session " + session.getId() + " message:" + cmsg);

                    session.getBasicRemote().sendText(cmsg);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        @Override
        public void locationEvent(String senderId, String roomName, String roomDescription, Map<String, String> exits,
                List<String> objects, List<String> inventory) {
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("type", "location");
            content.add("name", roomName);
            content.add("description", roomDescription);

            JsonObjectBuilder exitJson = Json.createObjectBuilder();
            for (Entry<String, String> e : exits.entrySet()) {
                exitJson.add(e.getKey(), e.getValue());
            }
            content.add("exits", exitJson.build());
            JsonArrayBuilder inv = Json.createArrayBuilder();
            for (String i : inventory) {
                inv.add(i);
            }
            content.add("pockets", inv.build());
            JsonArrayBuilder objs = Json.createArrayBuilder();
            for (String o : objects) {
                objs.add(o);
            }
            content.add("objects", objs.build());
            content.add("bookmark", counter.incrementAndGet());

            JsonObject json = content.build();
            for (Session session : activeSessions) {
                try {
                    String lmsg = "player," + senderId + "," + json.toString();
                    System.out.println("ROOM(LE): sending to session " + session.getId() + " message:" + lmsg);
                    session.getBasicRemote().sendText(lmsg);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        @Override
        public void listExitsEvent(String senderId, Map<String, String> exits) {

            JsonObjectBuilder exitMap = Json.createObjectBuilder();
            for (Entry<String, String> entry : exits.entrySet()) {
                exitMap.add(entry.getKey(), entry.getValue());
            }

            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add(Constants.TYPE, Constants.EXITS);
            content.add(Constants.CONTENT, exitMap.build());

            String lmsg = "player," + senderId + "," + content.build().toString();
            for (Session session : activeSessions) {
                if (session.isOpen()) {
                    try {
                        System.out.println("ROOM(LEE): sending to session " + session.getId() + " message:" + lmsg);
                        session.getBasicRemote().sendText(lmsg);
                    } catch (IOException io) {
                        throw new RuntimeException(io);
                    }
                }
            }
        }

        @Override
        public void exitEvent(String senderId, String message, String exitID) {
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("type", "exit");
            content.add("exitId", exitID);
            content.add("content", message);
            content.add("bookmark", counter.incrementAndGet());
            JsonObject json = content.build();
            for (Session session : activeSessions) {
                try {
                    String emsg = "playerLocation," + senderId + "," + json.toString();
                    System.out.println("ROOM(EE): sending to session " + session.getId() + " message:" + emsg);
                    session.getBasicRemote().sendText(emsg);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        Collection<Session> activeSessions = new HashSet<Session>();

        public void addSession(Session s) {
            activeSessions.add(s);
        }

        public void removeSession(Session s) {
            activeSessions.remove(s);
        }
    }

    /*
     * This is temporary.. the concierge is being rebuilt, but until then we
     * need to handle if it restarts before us.. so we'll just
     */
    private class ReRegisterRoomsForeverThread implements Runnable {
        private Collection<Room> rooms;
    
        public ReRegisterRoomsForeverThread(Collection<Room> rooms) {
            this.rooms = rooms;
        }

        @Override
        public void run() {
            try{
                ClientBuilder cb = ClientBuilder.newBuilder()
                        .property("com.ibm.ws.jaxrs.client.ssl.config", "defaultSSLConfig");

                Client client = cb.build();
                
                // add the apikey handler for the registration request.
                ApiKey apikey = new ApiKey("roomRegistration", registrationSecret);
                client.register(apikey);

                WebTarget target = client.target(conciergeLocation);

                for (Room room : rooms) {
                    Invocation.Builder builder = target.request(MediaType.APPLICATION_JSON);

                    net.wasdev.gameon.room.common.Room commonRoom = new net.wasdev.gameon.room.common.Room(room.getRoomId());
                    commonRoom.setAttribute("endPoint", thisLocation + "/ws/" + room.getRoomId());
                    commonRoom.setAttribute("startLocation", "" + room.isStarterLocation());
                    List<net.wasdev.gameon.room.common.Exit> exits = new ArrayList<net.wasdev.gameon.room.common.Exit>();
                    for (ExitDesc ed : room.getExits()) {
                        if (ed.handler.isVisible()) {
                            net.wasdev.gameon.room.common.Exit e = new net.wasdev.gameon.room.common.Exit();
                            e.setName(ed.direction.toString());
                            e.setDescription(ed.handler.getDescription(null, ed, room));
                            e.setRoom(ed.targetRoomId);
                            exits.add(e);
                        }
                    }
                    commonRoom.setExits(exits);

                    Response response = builder.post(Entity.json(commonRoom));
                    try {
                        if (Status.OK.getStatusCode() == response.getStatus()) {
                            //all is well, we don't log each room to avoid spam.
                        } else {
                            System.out.println("Error re-registering room provider : " + room.getRoomName() + " : status code "
                                    + response.getStatus());
                        }
                    } finally {
                        response.close();
                    }
                }
            }catch(Exception e){
                System.out.println("Reregister thread caught "+e.getMessage());
                e.printStackTrace();
                //by rethrowing here, the scheduled executor will shut us down.
            }
        }
    }

    private boolean getConfig() {
        try {
            conciergeLocation = (String) new InitialContext().lookup("conciergeLocation");
            System.out.println("CONFIG: conciergeLocation = " + conciergeLocation);
        } catch (NamingException e1) {
        }

        // BUG in Liberty as of 8.5.5.8, WebSocket URLs are normalized incorrectly.
//        try {
//            thisLocation = (String) new InitialContext().lookup("thisLocation");
//            System.out.println("CONFIG: thisLocation = " + thisLocation);
//        } catch (NamingException e1) {
//        }

            thisLocation = System.getenv("service_room");
            System.out.println("CONFIG: thisLocation = " + thisLocation);

        try {
            registrationSecret = (String) new InitialContext().lookup("registrationSecret");
            System.out.println("CONFIG: registrationSecret = " + (registrationSecret == null ? "null" : "***"));
        } catch (NamingException e) {
        }
        
        return conciergeLocation != null && thisLocation != null && registrationSecret != null;
    }

    private static class RoomWSConfig extends ServerEndpointConfig.Configurator {
        private final Room room;
        private final SessionRoomResponseProcessor srrp;

        public RoomWSConfig(Room room, SessionRoomResponseProcessor srrp) {
            this.room = room;
            this.srrp = srrp;
            this.room.setRoomResponseProcessor(srrp);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) {
            RoomWS r = new RoomWS(this.room, this.srrp);
            return (T) r;
        }
    }

    private Set<ServerEndpointConfig> registerRooms(Collection<Room> rooms) {

        if ( !getConfig() ) {
            // make sure parent attributes are set!
            throw new RuntimeException("MISSING configuration attributes!");
        }
        
        Set<ServerEndpointConfig> endpoints = new HashSet<ServerEndpointConfig>();
        for (Room room : rooms) {

            net.wasdev.gameon.room.common.Room commonRoom = new net.wasdev.gameon.room.common.Room(room.getRoomId());
            commonRoom.setAttribute("endPoint", thisLocation + "/ws/" + room.getRoomId());
            commonRoom.setAttribute("startLocation", "" + room.isStarterLocation());

            List<net.wasdev.gameon.room.common.Exit> exits = new ArrayList<net.wasdev.gameon.room.common.Exit>();
            for (ExitDesc ed : room.getExits()) {
                if (ed.handler.isVisible()) {
                    net.wasdev.gameon.room.common.Exit e = new net.wasdev.gameon.room.common.Exit();
                    e.setName(ed.direction.toString());
                    e.setDescription(ed.handler.getDescription(null, ed, room));
                    e.setRoom(ed.targetRoomId);
                    exits.add(e);
                }
            }
            commonRoom.setExits(exits);

            SessionRoomResponseProcessor srrp = new SessionRoomResponseProcessor();
            ServerEndpointConfig.Configurator config = new RoomWSConfig(room, srrp);

            endpoints.add(ServerEndpointConfig.Builder.create(RoomWS.class, "/ws/" + room.getRoomId())
                    .configurator(config).build());
        }
        
        try{
            ManagedScheduledExecutorService executor;
            executor = (ManagedScheduledExecutorService) new InitialContext().lookup("concurrent/execSvc");
            ReRegisterRoomsForeverThread r = new ReRegisterRoomsForeverThread(rooms);
            executor.scheduleAtFixedRate(r, 10, 10, TimeUnit.SECONDS);
        }catch(Exception e){
            System.out.println("Error creating room reregistrator.. "+e.getMessage());
            e.printStackTrace();
        }

        return endpoints;
    }

    @Override
    public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> endpointClasses) {
        return registerRooms(e.getRooms());
    }

    @Override
    public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned) {
        return null;
    }

}
