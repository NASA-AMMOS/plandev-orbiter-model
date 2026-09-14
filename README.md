> [!WARNING]
> **This repository is deprecated and no longer maintained.**
>
> PlanDev examples have moved to the consolidated [`NASA-AMMOS/plandev-examples`](https://github.com/NASA-AMMOS/plandev-examples) repository.
>
> This example has been replaced by the [`orbiter`](https://github.com/NASA-AMMOS/plandev-examples/tree/main/examples/05-orbiter) example in the new repository.


# PlanDev Multi-Mission Models - Orbiter Model

This repository houses an example PlanDev orbiter mission model, built from a collection of spacecraft subsystem models that can 
be configured, customized, and then run within PlanDev by a mission. By combining these models together with other models,
such as the [PlanDev Simple Power Model](https://github.com/NASA-AMMOS/aerie-simple-model-power) and [PlanDev Simple Data Model](https://github.com/NASA-AMMOS/aerie-simple-model-data),
a mission can build up an integrated spacecraft model quickly to perform mission trades and analyses.

The models in this
repository were originally derived from models created for the Blackbird planner, a java-based planning system developed
at NASA's Jet Propulsion Lab (JPL). There is also code included here for you to export and translate model data to the
free 3D visualization software [Cosmographia](https://cosmoguide.org/).

The following models are included in this repository:

- Geometry
- DSN (in work)
- Guidance Navigation and Control (in work)

Below you'll find short descriptions of each model and brief instructions on how to configure and run them. For general
instructions on how to compile models, see the instructions in the README of [mission model template repo](https://github.com/NASA-AMMOS/aerie-mission-model-template?tab=readme-ov-file#aerie-mission-model-template).
If you'd like to learn how to write PlanDev models, please see our [modeling tutorial](https://nasa-ammos.github.io/plandev-docs/tutorials/mission-modeling/introduction/).

## PlanDev & SeqDev Naming

As new mission communities have joined PlanDev, we've evolved our product focus and naming. What you need to know:

What to know:

* The planning tool is now named **PlanDev** and the sequencing tool is now named **SeqDev**
* Most repositories have been renamed, and the rest will be renamed soon. The repository code structure has not changed
* This repository will soon be renamed `plandev-orbiter-model`
* Published code packages (NPM, Java, and Docker images) still retain their **old** names but will be renamed in a future version
* Changes affecting your code will be announced in advance with upgrade guidance

Our latest docs page is https://nasa-ammos.github.io/plandev-docs/. Links to pages in the old docs site will be redirected to their new equivalent, but it is a good idea to update any links in your documents when possible.

## Getting Started

### Prerequisites

- Install **[OpenJDK Temurin LTS](https://adoptium.net/temurin/releases/?version=21)**, if you don't already have it. If you're on macOS, you can install [brew](https://brew.sh/) instead and then use the following command to install JDK 21:

  ```sh
  brew install --cask temurin@21
  ```
- Install **Git Large File Storage**. This repository uses Git LFS to manage large files, which you can install from [git-lfs.com](https://git-lfs.com/) or with `brew`:
  ```sh
  brew install git-lfs
  ```
  If you have never run LFS before, you must run the command `git lfs install` once after running the installer. 
- You need to create a **[personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic) in your GitHub account** that includes the `read-packages` scope, so that you can download the PlanDev Maven packages from the [GitHub Maven package registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry). Keep track of the username and token after you generate it.


### Quick Start

To start working with this model:

1. Clone this repository and `cd` to it
2. Generate a `.env` file from the template by running:

   ```cp .env.template .env```
3. Fill in the missing variables in the `.env` file with your Github username and access token from the Prerequisites section above:

   ```sh
   # in .env file
   GITHUB_USER="your_github_username_here"
   GITHUB_TOKEN="your_personal_access_token"
   ```
4. Run `git lfs pull` to download large files in the repo, like the SPICE kernel.
5. Run `docker compose up -d` to run PlanDev locally - after it starts up, it should be accessible on [http://localhost:80](http://localhost:80)

Once PlanDev is up and running with an empty database, you can use the following steps to populate it with a working mission model, constraints, and scheduling procedures:

#### Mission Model
The main mission model code is in the `missionmodel` folder. To build the mission model JAR (initially or after any changes to it), run:

```sh
./gradlew :missionmodel:build --refresh-dependencies
```

This will create the file `'missionmodel/build/libs/missionmodel.jar`, which you can upload to PlanDev using the UI or API.

#### Example Plan
Once you have built the mission model and uploaded it to PlanDev (via the "Models" page), you can use it to create Plans.
This repo contains an example Plan to demonstrate the model's capabilities: `Example_MarsSat_Plan.json`. To use it, 
go to the "Plans" page on PlanDev and use the Import button to select & import this JSON file. Set the "Model" to your
uploaded model, and create the plan.

The example plan covers a timespan from 2024-002 to 2024-004 - if you make your own custom plan, it should use the same
time range to ensure the simulation can run correctly with the included SPICE kernels.

A custom view for this plan is also included - access the view menu in the top right of the PlanDev and import the file
`MarsSat_Overview_View.json`

#### Scheduling Procedures and Constraints
To build scheduling procedures or procedural constraints, the following will be your process every time you iterate on these procedures

```sh
./gradlew scheduling:build
./gradlew scheduling:buildAllProcedureJars
```
or

```sh
./gradlew constraints:build
./gradlew constraints:buildAllProcedureJars
```

Your procedure jars will then be in `build/libs/OriginalSourceCodeFileName.jar` of either the `scheduling` or `constraints` directories.


## Geometry Model

The geometry model provides an easy way for you to compute geometric quantities based on [SPICE kernel data](https://naif.jpl.nasa.gov/naif/data.html)
based on your spacecraft's trajectory. Example quantities that the model can compute include spacecraft to body distance and speed,
sub-spacecraft illumination angles, Sun-body-spacecraft angle, orbit period and inclination, body half-angle size, and more!
There are also some "spawner" activities within the model that will schedule activities to represent spacecraft eclipses,
occultations, and periapsis and apoapsis times.

Since the geometry model requires SPICE data to work, you'll need to include kernels for your spacecraft trajectory and
any other bodies to which you are computing relative geometry. There are some [example kernels](spice/kernels) included
in this repository for the Juno mission you can use to try out the model. When you are ready to use your own kernels,
you will want to update the [latest_meta_kernel.tm](spice/kernels/latest_meta_kernel.tm) file to point to your kernels.

Note: In order for PlanDev to read SPICE files, you must mount a folder on your filesystem so that it’s shared with the
Docker containers in which PlanDev runs. The [`docker-compose.yml`]() file in this repo already does this for you, so if you
start PlanDev from that file and store your kernels in the [spice/kernels](spice/kernels) directory, you shouldn't have
to do anything special.

Bodies and geometric quantities you want to calculate are all configured in the [`default_geometry_config.json`](src/main/resources/missionmodel/default_geometry_config.json),
which gets bundled into the mission model jar when you compile the model.

Finally, in order to point the model to the right spacecraft to compute geometry against, you need to tell the model the
SPICE ID of that spacecraft. You can either do this via simulation configuration after you have uploaded the model or change
the default value in the [Configuration](src/main/java/missionmodel/Configuration.java) class.

## Acknowledgements

A special thanks to Chris Lawler and Flora Ridenhour, the original developers of the Blackbird planner, who have graciously provided the Blackbird multi-mission models to the PlanDev team as a starting point for the models in this repository.
