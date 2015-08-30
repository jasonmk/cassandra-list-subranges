package br.com.chaordic.cassandra;

import java.util.LinkedList;
import java.util.List;

import org.apache.cassandra.thrift.AuthenticationException;
import org.apache.cassandra.thrift.AuthenticationRequest;
import org.apache.cassandra.thrift.AuthorizationException;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.CfSplit;
import org.apache.cassandra.thrift.EndpointDetails;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.TokenRange;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import com.google.common.collect.ImmutableMap;

public class ListSubRanges {


    private static final String DEFAULT_NUMBER_OF_PARTITIONS = "32768"; //32K partitions
    private static final String PARTITIONER_RANGE_OPT = "partitioner-range";
    private static final String START_TOKEN_OPT = "start-token";
    private static final String END_TOKEN_OPT = "end-token";
    private static final String PARTITIONS_OPT = "num-partitions";
    private static final String CASSANDRA_LISTEN_ADDRESS_OPT = "listen-address";
    private static final String OMIT_HEADER_OPT = "omit-header";
    private static final String JSON_OUTPUT_OPT = "json-output";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final Options options;
    static {
        options = new Options();
        options.addOption("st", START_TOKEN_OPT, true,
                "Calculate subranges of the range with this start token.");
        options.addOption("et", END_TOKEN_OPT, true,
                "Calculate subranges of the range with this end token.");
        options.addOption("l", CASSANDRA_LISTEN_ADDRESS_OPT, true,
                "Node listen address (if different from broadcast address). (default: nodeIpAddress)");
        options.addOption("pr", PARTITIONER_RANGE_OPT, false,
                "Only consider the first range returned by the partitioner.");
        options.addOption("n", PARTITIONS_OPT, true,
                "Number of partitions per subsplit. (default 32K)");
        options.addOption("o", OMIT_HEADER_OPT, false,
                "Omit the header from the standard output.");
        options.addOption("j", JSON_OUTPUT_OPT, false,
                "Output token ranges as a JSON array.");
        options.addOption("u", USERNAME, false,
                "Username to authenticate with.");
        options.addOption("p", PASSWORD, false,
                "Password to authenticate with.");
    }

    static Cassandra.Client client;
    static String nodeListenAddress;
    static String nodeIp;
    static String keyspace;
    static String columnFamily;
    static int keysPerSplit;
    static String startToken;
    static String endToken;
    static String username;
    static String password;

    static boolean firstRangeOnly;
    static boolean omitHeader;
    static boolean jsonOutput;

    public static void main(String[] args) throws Exception {
        if (args.length < 3){
            printHelpAndExit(options);

        }

        parseOpts(args);
        initClient(username, password);

        if (!omitHeader && !jsonOutput) {
            printHeader();
        }

        if (startToken != null && endToken != null) {
            //specific range
            retrieveAndPrintSubsplits(columnFamily, keysPerSplit, startToken, endToken, client);
        } else {
            //node range
            List<TokenRange> nodeRanges = retrieveNodeRanges();
            for (TokenRange tokenRange : nodeRanges) {
                retrieveAndPrintSubsplits(columnFamily, keysPerSplit, tokenRange.start_token, tokenRange.end_token, client);
            }
        }
    }

    private static void retrieveAndPrintSubsplits(String columnFamily, int keysPerSplit, String startToken, String endToken, Cassandra.Client client)
            throws InvalidRequestException, TException {
        List<CfSplit> cfSplits = client.describe_splits_ex(columnFamily, startToken, endToken, keysPerSplit);
        if (jsonOutput)
          System.out.println("[");
        boolean first = true;
        for (CfSplit cfSplit : cfSplits) {
            if (jsonOutput) {
                if (!first)
                    System.out.println(",");
                else
                    first = false;
                System.out.printf("[%s,%s]", cfSplit.start_token, cfSplit.end_token);
            } else {
                String st = String.format("%1$-39s", cfSplit.start_token);
                String et = String.format("%1$-39s", cfSplit.end_token);
                System.out.printf("%s %s %d\n", st, et, cfSplit.row_count);
            }
        }
        if (jsonOutput)
          System.out.println("\n]");
    }

    private static void printHeader() {
        System.out.println("Start Token                             End Token                               Estimated Size");
        System.out.println("------------------------------------------------------------------------------------------------");
    }

    private static List<TokenRange> retrieveNodeRanges() throws InvalidRequestException, TException {
        List<TokenRange> nodeRanges = new LinkedList<TokenRange>();
        List<TokenRange> localRing = client.describe_local_ring(keyspace);
        for (TokenRange tokenRange : localRing) {
            List<EndpointDetails> rangeEndpoints = tokenRange.getEndpoint_details();
            for (EndpointDetails endpointDetails : rangeEndpoints) {
                if (nodeIp.equals(endpointDetails.getHost())) {
                    nodeRanges.add(tokenRange);
                }
                if (firstRangeOnly) break;
            }
        }
        return nodeRanges;
    }

    private static void initClient(String username, String password) throws InvalidRequestException, TException,
        AuthenticationException, AuthorizationException {
        TTransport tr = new TFramedTransport(new TSocket(nodeListenAddress, 9160));
        tr.open();
        client = new Cassandra.Client(new TBinaryProtocol(tr));
        client.set_keyspace(keyspace);

        if(username != null && password != null) {
            client.login(new AuthenticationRequest(ImmutableMap.of("username", username, "password", password)));
        }
    }

    private static void parseOpts(String[] args) throws ParseException {
        CommandLineParser parser = new GnuParser();
        CommandLine cmdLine = parser.parse(options, args);
        args = cmdLine.getArgs();
        nodeIp = args[0];
        keyspace = args[1];
        columnFamily = args[2];

        keysPerSplit = Integer.valueOf(cmdLine.getOptionValue(PARTITIONS_OPT, DEFAULT_NUMBER_OF_PARTITIONS));
        nodeListenAddress = cmdLine.getOptionValue(CASSANDRA_LISTEN_ADDRESS_OPT, nodeIp);
        startToken = cmdLine.getOptionValue(START_TOKEN_OPT);
        endToken = cmdLine.getOptionValue(END_TOKEN_OPT);
        firstRangeOnly = cmdLine.hasOption(PARTITIONER_RANGE_OPT);
        omitHeader = cmdLine.hasOption(OMIT_HEADER_OPT);
        jsonOutput = cmdLine.hasOption(JSON_OUTPUT_OPT);
    }

    private static void printHelpAndExit(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        String header = "Lists CF subranges for a particular node or token range.\n" +
                        "The output of this command can be used as input for subrange repair.";
        formatter.printHelp("java -jar list-subranges.jar <nodeIpAddress> <keySpace> <columnFamily>", header, options, null);
        System.exit(1);
    }
}
