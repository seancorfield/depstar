# Deploying a Library

After you've generated your JAR file with a `pom.xml` file, you can use [`deps-deploy`](https://github.com/slipset/deps-deploy) to deploy to Clojars (or other Maven-like repositories) -- or you could use the `mvn` command below.

## Deploying with `deps-deploy`

As noted above, you could also use `deps-deploy` to deploy your JAR file to Clojars.
Add the following alias to your `deps.edn` file:

```clojure
    ;; version 0.1.5 was the most recent as of 2021-01-29:
    :deploy {:replace-deps {slipset/deps-deploy {:mvn/version "RELEASE"}}
             :exec-fn deps-deploy.deps-deploy/deploy
             :exec-args {:installer :remote :artifact "MyProject.jar"}}
```

Then:


```bash
clojure -X:deploy
```

This expects your Clojars username to be in the `CLOJARS_USERNAME` environment variable and your Clojars **token** to be in the `CLOJARS_PASSWORD` environment variable.

## Deploying with Maven

```bash
mvn deploy:deploy-file -Dfile=MyProject.jar -DpomFile=pom.xml -DrepositoryId=clojars -Durl=https://clojars.org/repo/
```

This assumes that you have credentials for your chosen repository in your `~/.m2/settings.xml` file. It should look like this (with your username and **token**):

```xml
<settings>
  <servers>
    <server>
      <id>clojars</id>
      <username>someperson</username>
      <password>topsecret</password>
    </server>
  </servers>
</settings>
```

If you only want to install the artifact locally (for use in local development, similar to `lein install`), you can use the following `mvn` command:

```bash
mvn install:install-file -Dfile=MyProject.jar -DpomFile=pom.xml
```

After that you can require the dependency coordinates as usual, using the **group ID**, **artifact ID**, and **version** that you had setup in the `pom.xml` file.

## My Deployment Process

My libraries all follow a MAJOR.MINOR.COMMITS pattern for versioning, but this process could apply to
your preferred versioning scheme as well.

When I'm getting ready to cut a new release, and I have all the enhancements and bug fixes committed and
pushed, I perform one last commit with the following updates:

* Run `git pull` to ensure I have the latest changes locally.
* Select a version number for the release
  * For me, that's figured out by running `git rev-list --count HEAD` and adding one to get the COMMITS part of the release I'm about to make, as well as deciding whether MAJOR/MINOR also need an update (generally not).
* Update the `CHANGELOG.md` file to list the new release version and date and ensure all the changes since the previous release are documented.
* Update the `README.md` and other documentation to show the new version number everywhere.
* Build the JAR file, also specifying `:sync-pom true :version '"x.y.z"'` to update the `<version>` and SCM `<tag>` in the `pom.xml` file at the same time.
* Perform one last full test suite pass!
* Verify all the diffs, then `git add`, `git commit`, and `git push`.
* Then I draft a new release on GitHub and use the information in `CHANGELOG.md` in the release notes, and cut the release on GitHub.
* Now I `git pull`, check that only a new tag came back.
* Perform a final full test suite pass (yes, even though I just ran it before the `git push`/`git pull` cycle -- I'm paranoid, okay?).
* Build and publish the JAR file: `clojure -X:jar && clojure -X:deploy`

> Note: all my libraries have `:jar` and `:deploy` as aliases in their `deps.edn` files which supply appropriate default values for the `:exec-args`. For example, from `clj-new`:

```clj
    ;; override the example :jar alias with a specific one:
    :jar {:replace-deps {com.github.seancorfield/depstar {:mvn/version "2.1.253"}}
          :exec-fn hf.depstar/jar
          :exec-args {:jar "clj-new.jar" :sync-pom true}}
    :deploy {:replace-deps {slipset/deps-deploy {:mvn/version "0.1.5"}}
            :exec-fn deps-deploy.deps-deploy/deploy
            :exec-args {:installer :remote :artifact "clj-new.jar"}}
```

> I use a generic name for the JAR file so it doesn't need to be updated: both `depstar` and `deps-deploy` use the `<groupId>`, `<artifactId>`, and `<version>` information from `pom.xml` to ascertain the correct coordinates and version to use. If your project was created initially by `clj-new`, it should also have `:jar` and `:deploy` aliases in the `deps.edn` file that was generated.
