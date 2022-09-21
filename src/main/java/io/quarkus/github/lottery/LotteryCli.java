package io.quarkus.github.lottery;

import java.io.IOException;

import org.kohsuke.github.GHPermissionType;

import com.github.rvesse.airline.annotations.Cli;
import com.github.rvesse.airline.annotations.Command;

import io.quarkiverse.githubapp.command.airline.Permission;
import io.quarkus.arc.Arc;

@Cli(name = "/lottery", commands = { LotteryCli.DrawCommand.class })
public class LotteryCli {

    interface Commands {
        void run() throws IOException;
    }

    @Command(name = "draw")
    @Permission(GHPermissionType.ADMIN)
    static class DrawCommand implements Commands {
        @Override
        public void run() throws IOException {
            // Cannot inject the service for some reason,
            // as Airline uses reflection and performs calls to setAccessible recursively.
            Arc.container().instance(LotteryService.class).get().draw();
        }
    }
}