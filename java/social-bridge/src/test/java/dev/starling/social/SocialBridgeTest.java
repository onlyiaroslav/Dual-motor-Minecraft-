package dev.starling.social;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test JNI end-to-end del layer social: lista amici persistente, codice
 * monouso, mini-server auth e rifiuto del riuso. Richiede
 * {@code libstarling_social.so} in {@code java.library.path}.
 */
public final class SocialBridgeTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testTtl();
        testListaPersistente();
        testInvitoEServer();
        testRiusoNegato();
        testChiusura();
        System.out.printf("%nTest SocialBridge: %d passati, %d falliti%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
            System.out.println("  ok: " + msg);
        } else {
            failed++;
            System.out.println("  FAIL: " + msg);
        }
    }

    private static Path tempFriendsFile() throws IOException {
        return Files.createTempDirectory("starling-social").resolve("friends.txt");
    }

    private static void testTtl() {
        System.out.println("testTtl");
        check(SocialBridge.tokenTtlSeconds() == 300, "TTL codici = 300 secondi (5 minuti)");
    }

    private static void testListaPersistente() throws Exception {
        System.out.println("testListaPersistente");
        Path file = tempFriendsFile();
        try (SocialBridge bridge = new SocialBridge(file.toString())) {
            String uuid = bridge.addFriend("Steve_01");
            check(uuid != null && uuid.length() == 36, "addFriend ritorna UUID locale");
            check(bridge.listFriends().length == 1, "lista con 1 amico");
            check(bridge.listFriends()[0].equals("Steve_01|" + uuid + "|0"),
                    "formato nickname|uuid|online");

            boolean dup = false;
            try {
                bridge.addFriend("Steve_01");
            } catch (IllegalStateException e) {
                dup = true;
            }
            check(dup, "nickname duplicato rifiutato");

            boolean invalid = false;
            try {
                bridge.addFriend("x");
            } catch (IllegalStateException e) {
                invalid = true;
            }
            check(invalid, "nickname invalido rifiutato");

            check(bridge.addFriend("Alex") != null, "secondo amico aggiunto");
            check(bridge.removeFriend("Alex"), "amico rimosso");
            check(!bridge.removeFriend("Alex"), "rimozione idempotente");
        }
        // Persistenza: nuova istanza sullo stesso file.
        try (SocialBridge reopened = new SocialBridge(file.toString())) {
            check(reopened.listFriends().length == 1, "lista ricaricata dal file");
            check(reopened.listFriends()[0].startsWith("Steve_01|"), "amico persistito");
        }
    }

    private static void testInvitoEServer() throws Exception {
        System.out.println("testInvitoEServer");
        Path file = tempFriendsFile();
        try (SocialBridge bridge = new SocialBridge(file.toString())) {
            String uuid = bridge.addFriend("Steve_01");
            String code = bridge.generateInvite("127.0.0.1", 25565);
            check(code != null && code.length() == 8, "codice monouso di 8 caratteri");

            int port = bridge.startAuthServer(0);
            check(port > 0, "server auth avviato su porta effimera " + port);
            check(bridge.authPort() == port, "authPort coerente");

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), true);

                out.println("LOGIN Steve_01");
                check("CODE_REQUIRED".equals(in.readLine()), "login offline chiede il codice");

                out.println("TOKEN " + code);
                String welcome = in.readLine();
                check(welcome != null && welcome.startsWith("WELCOME " + uuid + " Steve_01 offline"),
                        "profilo iniettato con UUID locale e online_mode=false: " + welcome);
            }

            bridge.stopAuthServer();
            check(bridge.authPort() == 0, "server auth fermato");
        }
    }

    private static void testRiusoNegato() throws Exception {
        System.out.println("testRiusoNegato");
        Path file = tempFriendsFile();
        try (SocialBridge bridge = new SocialBridge(file.toString())) {
            bridge.addFriend("Steve_01");
            String code = bridge.generateInvite("127.0.0.1", 25565);
            int port = bridge.startAuthServer(0);

            // Primo ingresso: valido.
            check(attemptJoin(port, "Steve_01", code).startsWith("WELCOME"),
                    "primo ingresso riuscito");
            // Secondo ingresso con lo stesso codice: negato.
            String second = attemptJoin(port, "Alex", code);
            check(second.equals("DENY invalid_code"),
                    "riuso del codice negato: " + second);
        }
    }

    private static String attemptJoin(int port, String nick, String code) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println("LOGIN " + nick);
            in.readLine(); // CODE_REQUIRED
            out.println("TOKEN " + code);
            return in.readLine();
        }
    }

    private static void testChiusura() throws Exception {
        System.out.println("testChiusura");
        Path file = tempFriendsFile();
        SocialBridge bridge = new SocialBridge(file.toString());
        bridge.addFriend("Steve_01");
        bridge.close();
        boolean closed = false;
        try {
            bridge.addFriend("Alex");
        } catch (IllegalStateException e) {
            closed = true;
        }
        check(closed, "uso dopo close() rifiutato");
    }
}
