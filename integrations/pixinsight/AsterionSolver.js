/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Asterion Solver for PixInsight: solves the active image blindly with the asterion command line
 * tool, and writes the astrometric solution in the image, where PixInsight's own tools find it:
 * Image > Astrometric Solution, AnnotateImage, MosaicByCoordinates...
 *
 * The image is written to a temporary FITS file with its keywords, which asterion reads for hints
 * unless told otherwise. The solution is stored the way ImageSolver stores its own, as properties
 * of the image built by PixInsight's astrometry library from the WCS keywords of the solution: a
 * TAN projection. PixInsight has no use for the SIP distortion polynomials of the solution, which
 * aren't kept: ImageSolver can refine the solution with its own distortion model.
 */

#engine v8
#feature-id    Asterion > Asterion Solver
#feature-info  Solves the active image blindly with Asterion Solver, in a fraction of a second, \
               and writes the astrometric solution in the image.
#script-id     AsterionSolver

CoreApplication.ensureMinimumVersion( 1, 9, 4 );

#include <pjsr/FrameStyle.jsh>
#include <pjsr/TextAlign.jsh>
#include <pjsr/StdButton.jsh>
#include <pjsr/StdIcon.jsh>
#include <pjsr/UndoFlag.jsh>
#include <pjsr/DataType.jsh>
#include <pjsr/ProcessExitStatus.jsh>
// the astrometry library keeps its settings under this name
#define SETTINGS_MODULE "ASTERION"
#include <pjsr/astrometry/AstrometricMetadata.js>

#define ASTERION_TITLE   "Asterion Solver"
#define ASTERION_VERSION "0.1"
#define ASTERION_SETTINGS "AsterionSolver"

/*
 * The parameters of a run, kept in the settings of PixInsight between runs.
 */
class AsterionParameters
{
   constructor()
   {
      // the asterion executable: a path, or its name when it is in the PATH
      this.executable = "asterion";
      // whether the position and the focal length found in the keywords of the image are used
      // to speed the search up: a wrong position only costs a fraction of a second
      this.useHints = true;
      // whether the deep sky objects of the field are listed in the console
      this.listObjects = true;
   }

   load()
   {
      let executable = Settings.read( ASTERION_SETTINGS + "/executable", DataType_String );
      if ( Settings.lastReadOK && executable.length > 0 )
         this.executable = executable;
      let useHints = Settings.read( ASTERION_SETTINGS + "/useHints", DataType_Boolean );
      if ( Settings.lastReadOK )
         this.useHints = useHints;
      let listObjects = Settings.read( ASTERION_SETTINGS + "/listObjects", DataType_Boolean );
      if ( Settings.lastReadOK )
         this.listObjects = listObjects;
   }

   save()
   {
      Settings.write( ASTERION_SETTINGS + "/executable", DataType_String, this.executable );
      Settings.write( ASTERION_SETTINGS + "/useHints", DataType_Boolean, this.useHints );
      Settings.write( ASTERION_SETTINGS + "/listObjects", DataType_Boolean, this.listObjects );
   }
}

/*
 * Runs the asterion executable with arguments, and waits for it: the path may be wrong, so this
 * never throws.
 *
 * @param onOutput a function given each piece of the output as it comes, or null
 * @return {ok, exitCode, stdout, stderr, error}, error being the reason when it could not run
 */
function asterionRun( executable, args, timeoutMillis, onOutput )
{
   let result = { ok: false, exitCode: -1, stdout: "", stderr: "", error: null };
   if ( executable == null || executable.trim().length == 0 )
   {
      result.error = "The path of the asterion executable is empty.";
      return result;
   }
   let process = new ExternalProcess;
   try
   {
      if ( onOutput != null )
      {
         // the output is relayed as it comes, and kept
         process.onStandardOutputDataAvailable = () =>
         {
            let text = String( process.stdout );
            result.stdout += text;
            onOutput( text );
         };
         process.onStandardErrorDataAvailable = () =>
         {
            let text = String( process.stderr );
            result.stderr += text;
            onOutput( text );
         };
      }
      process.start( executable, args );
      if ( !process.waitForStarted( 5000 ) )
      {
         result.error = "Could not start " + executable + ": is Asterion Solver installed, and is its path right?";
         return result;
      }
      let start = Date.now();
      while ( process.isStarting )
         CoreApplication.processEvents();
      while ( process.isRunning )
      {
         CoreApplication.processEvents();
         if ( timeoutMillis > 0 && Date.now() - start > timeoutMillis )
         {
            process.terminate();
            result.error = executable + " did not answer within " + (timeoutMillis / 1000) + " seconds.";
            return result;
         }
      }
      if ( onOutput == null )
      {
         result.stdout = String( process.stdout );
         result.stderr = String( process.stderr );
      }
      result.exitCode = process.exitCode;
      result.ok = process.exitStatus == ProcessExitStatus_Normal && process.exitCode != 2;
      if ( !result.ok && result.error == null )
         result.error = executable + " failed" + (result.stderr.trim().length > 0 ? ": " + result.stderr.trim() : " with exit code " + result.exitCode);
      return result;
   }
   catch ( error )
   {
      result.error = "Could not run " + executable + ": " + error.message;
      return result;
   }
}

/*
 * Checks the executable: its version, when it runs.
 *
 * @return {ok, version, error}
 */
function asterionProbe( executable )
{
   let run = asterionRun( executable, ["--version"], 10000, null );
   if ( !run.ok )
      return { ok: false, version: null, error: run.error };
   let version = run.stdout.trim();
   if ( !version.startsWith( "Asterion Solver" ) )
      return { ok: false, version: null, error: executable + " is not Asterion Solver: it printed \"" + version.substring( 0, 40 ) + "\"" };
   return { ok: true, version: version, error: null };
}

/*
 * The catalogs: the installed ones and the ones which can be downloaded, from asterion.
 *
 * @return {ok, directory, installed, available, error}
 */
function asterionCatalogs( executable )
{
   let run = asterionRun( executable, ["--list-catalogs", "--json"], 30000, null );
   if ( !run.ok )
      return { ok: false, error: run.error };
   try
   {
      let catalogs = JSON.parse( run.stdout );
      catalogs.ok = true;
      catalogs.error = null;
      return catalogs;
   }
   catch ( error )
   {
      return { ok: false, error: "Unexpected answer of " + executable + " --list-catalogs: " + error.message };
   }
}

/*
 * The keywords of the image which describe its astrometric solution, replaced by the ones of the
 * new solution: WCS, SIP polynomials, and the keywords of ImageSolver.
 */
const ASTERION_SOLUTION_KEYWORDS =
   /^(CTYPE\d|CRPIX\d|CRVAL\d|CD\d_\d|CDELT\d|CROTA\d|PC\d_\d|CUNIT\d|WCSAXES|LONPOLE|LATPOLE|EQUINOX|RADESYS|RADECSYS|[AB]P?_ORDER|[AB]P?_\d+_\d+|PV\d_\d+|IMAGEW|IMAGEH)$/;

/*
 * Solves an image window, and writes the solution in it.
 *
 * @return true if the image was solved
 */
function asterionSolve( window, params )
{
   console.show();
   console.writeln( "<end><cbr><b>" + ASTERION_TITLE + " " + ASTERION_VERSION + "</b>" );
   console.flush();

   let view = window.mainView;
   let image = view.image;
   let file = File.systemTempDirectory + "/asterion-" + Date.now() + ".fits";

   /*
    * The image, in a temporary FITS file, with the keywords of the image, which may give the
    * position and the focal length. Its rows are written bottom-up whatever the preferences, as
    * the solution refers to the rows of the file.
    */
   let format = new FileFormat( ".fits", false/*toRead*/, true/*toWrite*/ );
   if ( format.isNull )
      throw new Error( "No installed file format can write FITS files." );
   let writer = new FileFormatInstance( format );
   if ( writer.isNull )
      throw new Error( "Could not instantiate the FITS file format." );
   try
   {
      if ( !writer.create( file, "bottom-up" ) )
         throw new Error( "Could not create the temporary file " + file );
      let description = new ImageDescription;
      description.bitsPerSample = 16;
      description.ieeefpSampleFormat = false;
      if ( !writer.setOptions( description ) )
         throw new Error( "Could not set the options of the temporary file " + file );
      writer.keywords = window.keywords;
      if ( !writer.writeImage( image ) )
         throw new Error( "Could not write the temporary file " + file );
   }
   finally
   {
      writer.close();
   }

   let result;
   try
   {
      let args = [ "--json", "--plain" ];
      if ( !params.useHints )
         args.push( "--no-hints" );
      args.push( file );
      console.writeln( "Running " + params.executable + " " + args.join( " " ) );
      console.flush();

      let run = asterionRun( params.executable, args, 0, null );
      if ( run.stderr.trim().length > 0 )
         console.writeln( run.stderr.trim() );
      if ( !run.ok )
         throw new Error( run.error );
      if ( run.stdout.trim().length == 0 )
         throw new Error( params.executable + " printed nothing." );
      result = JSON.parse( run.stdout );
      if ( Array.isArray( result ) )
         result = result[0];
   }
   finally
   {
      if ( File.exists( file ) )
         File.remove( file );
   }

   let timing = result.timing;
   if ( !result.solved )
   {
      console.warningln( "Not solved: " + result.stars + " stars detected, " + timing.totalMillis.toFixed( 0 ) + " ms." );
      return false;
   }

   console.writeln( "Solved in " + timing.totalMillis.toFixed( 0 ) + " ms (read " + timing.readMillis.toFixed( 0 )
      + ", detect " + timing.detectMillis.toFixed( 0 ) + ", solve " + timing.solveMillis.toFixed( 0 ) + ")" );
   console.writeln( "  Center:    " + result.ra.toFixed( 5 ) + "°, " + (result.dec >= 0 ? "+" : "") + result.dec.toFixed( 5 ) + "°" );
   console.writeln( "  Scale:     " + result.scale.toFixed( 4 ) + " \"/px" );
   console.writeln( "  Field:     " + result.fieldWidth.toFixed( 3 ) + "° x " + result.fieldHeight.toFixed( 3 ) + "°" );
   if ( result.focalLength )
      console.writeln( "  Focal:     " + result.focalLength.toFixed( 0 ) + " mm (" + result.pixelSize.toFixed( 2 ) + " µm pixels)" );
   console.writeln( "  Rotation:  " + result.rotation.toFixed( 3 ) + "° east of north" + (result.flipped ? ", flipped" : "") );
   console.writeln( "  Stars:     " + result.matchedStars + " matched out of " + result.stars + ", RMS " + result.rmsArcsec.toFixed( 2 )
      + "\" (" + (result.rmsArcsec / result.scale).toFixed( 2 ) + " px)" );
   if ( params.listObjects && result.objects && result.objects.length > 0 )
   {
      let names = result.objects.map( o => o.name + (o.commonName ? " (" + o.commonName + ")" : "") );
      let shown = names.slice( 0, 12 );
      console.writeln( "  Objects:   " + shown.join( ", " ) + (names.length > shown.length ? ", and " + (names.length - shown.length) + " more" : "") );
   }

   /*
    * The solution replaces the one of the image. The WCS keywords of the TAN projection are
    * written, PixInsight's astrometry library reads them and stores the solution as properties
    * of the image, as ImageSolver does, and the astrometric solution of the image is rebuilt.
    */
   let keywords = window.keywords.filter( k => !ASTERION_SOLUTION_KEYWORDS.test( k.name ) );
   let comment = "Asterion Solver " + (result.index ? result.index : "");
   for ( let name in result.wcs )
   {
      if ( /^[AB]P?_/.test( name ) )
         continue;
      let value = result.wcs[name];
      if ( name == "CTYPE1" || name == "CTYPE2" )
         value = value.replace( "-SIP", "" );
      let text = (typeof value == "string") ? "'" + value + "'" : String( value ).toUpperCase();
      keywords.push( new FITSKeyword( name, text, comment ) );
   }
   view.beginProcess( UndoFlag_Keywords | UndoFlag_AstrometricSolution );
   let metadata = new AstrometricMetadata;
   for ( let property of view.properties )
      if ( property.startsWith( "PCL:AstrometricSolution:" ) )
         metadata.RemoveProperty( view, property );
   window.keywords = keywords;
   metadata.ExtractMetadata( window );
   if ( metadata.ref_I_G_linear == null )
   {
      view.endProcess();
      throw new Error( "PixInsight could not read the solution from its keywords." );
   }
   /*
    * The library guesses the row order of the file the keywords refer to from the sign of the
    * matrix, which is wrong for mirrored images: the file was written bottom-up, so the
    * transformation from the pixels of the image to the projection is recomputed with that
    * order. FITS coordinates count from 1 at the center of the first pixel, image coordinates
    * from 0 at its corner.
    */
   let wcs = result.wcs;
   let ref_F_G = new Matrix( wcs.CD1_1, wcs.CD1_2, -wcs.CD1_1 * wcs.CRPIX1 - wcs.CD1_2 * wcs.CRPIX2,
                             wcs.CD2_1, wcs.CD2_2, -wcs.CD2_1 * wcs.CRPIX1 - wcs.CD2_2 * wcs.CRPIX2,
                             0, 0, 1 );
   let ref_F_I = new Matrix( 1,  0,               -0.5,
                             0, -1, image.height + 0.5,
                             0,  0,                1 );
   metadata.ref_I_G_linear = ref_F_G.mul( ref_F_I.inverse() );
   metadata.ref_I_G = metadata.ref_I_G_linear;
   metadata.ref_G_I = metadata.ref_I_G.inverse();
   metadata.SaveKeywords( window, false/*beginProcess*/ );
   metadata.SaveProperties( window, ASTERION_TITLE + " " + ASTERION_VERSION, result.index ? result.index : "" );
   window.regenerateAstrometricSolution();
   view.endProcess();
   if ( window.hasAstrometricSolution )
      console.writeln( "<end><cbr>" + window.astrometricSolutionSummary() );
   else
      console.warningln( "The solution was written, but PixInsight could not build an astrometric solution from it." );
   console.flush();
   return true;
}

/*
 * The dialog: the path of the executable, and the options.
 */
class AsterionDialog extends Dialog
{
   constructor( params )
   {
      super();
      this.params = params;
      this.windowTitle = ASTERION_TITLE;

      let labelWidth = this.font.width( "Executable:" ) + 8;

      this.imageLabel = new Label( this );
      this.imageLabel.text = "Image:";
      this.imageLabel.textAlignment = TextAlign_Right | TextAlign_VertCenter;
      this.imageLabel.minWidth = labelWidth;

      this.imageList = new ViewList( this );
      this.imageList.getMainViews();
      this.imageList.toolTip = "The image to solve.";
      let active = ImageWindow.activeWindow;
      if ( !active.isNull )
         this.imageList.currentView = active.mainView;
      this.imageList.onViewSelected = view => { this.updateButtons(); };

      this.imageSizer = new HorizontalSizer;
      this.imageSizer.spacing = 4;
      this.imageSizer.add( this.imageLabel );
      this.imageSizer.add( this.imageList, 100 );

      this.help = new Label( this );
      this.help.frameStyle = FrameStyle_Box;
      this.help.margin = 6;
      this.help.wordWrapping = true;
      this.help.useRichText = true;
      this.help.text = "<p><b>" + ASTERION_TITLE + " " + ASTERION_VERSION + "</b> &mdash; solves the active image blindly "
         + "with Asterion Solver, and writes the astrometric solution in the image.</p>"
         + "<p>The <tt>asterion</tt> command line tool must be installed, with a catalog: "
         + "<tt>asterion --download-catalog tycho2</tt>.</p>";

      this.executableLabel = new Label( this );
      this.executableLabel.text = "Executable:";
      this.executableLabel.textAlignment = TextAlign_Right | TextAlign_VertCenter;
      this.executableLabel.minWidth = labelWidth;

      this.executableEdit = new Edit( this );
      this.executableEdit.text = params.executable;
      this.executableEdit.toolTip = "The asterion executable: its path, or just its name when it is in the PATH.";
      this.executableEdit.onTextUpdated = text => { this.params.executable = text.trim(); };
      this.executableEdit.onEditCompleted = () => { this.refresh(); };

      this.executableButton = new ToolButton( this );
      this.executableButton.icon = this.scaledResource( ":/icons/select-file.png" );
      this.executableButton.setScaledFixedSize( 20, 20 );
      this.executableButton.toolTip = "Select the asterion executable";
      this.executableButton.onClick = () =>
      {
         let dialog = new OpenFileDialog;
         dialog.caption = "Select the asterion executable";
         dialog.multipleSelections = false;
         if ( dialog.execute() )
         {
            this.params.executable = dialog.fileName;
            this.executableEdit.text = dialog.fileName;
            this.refresh();
         }
      };

      // what the executable answers: its version, or why it doesn't run
      this.statusLabel = new Label( this );
      this.statusLabel.wordWrapping = true;
      this.statusLabel.useRichText = true;

      /*
       * The catalogs: installed, or available for download.
       */
      this.catalogsBox = new TreeBox( this );
      this.catalogsBox.alternateRowColor = true;
      this.catalogsBox.multipleSelection = false;
      this.catalogsBox.rootDecoration = false;
      this.catalogsBox.numberOfColumns = 3;
      this.catalogsBox.headerVisible = true;
      this.catalogsBox.setHeaderText( 0, "Catalog" );
      this.catalogsBox.setHeaderText( 1, "Status" );
      this.catalogsBox.setHeaderText( 2, "Content" );
      this.catalogsBox.setScaledMinHeight( 120 );
      this.catalogsBox.onCurrentNodeUpdated = () => { this.updateButtons(); };

      this.downloadButton = new PushButton( this );
      this.downloadButton.text = "Download";
      this.downloadButton.icon = this.scaledResource( ":/icons/download.png" );
      this.downloadButton.toolTip = "Downloads and installs the selected catalog, which takes minutes and hundreds of megabytes: "
         + "the console shows the progress.";
      this.downloadButton.onClick = () => { this.download(); };

      this.refreshButton = new PushButton( this );
      this.refreshButton.text = "Refresh";
      this.refreshButton.icon = this.scaledResource( ":/icons/refresh.png" );
      this.refreshButton.onClick = () => { this.refresh(); };

      this.catalogsButtonsSizer = new HorizontalSizer;
      this.catalogsButtonsSizer.spacing = 6;
      this.catalogsButtonsSizer.addStretch();
      this.catalogsButtonsSizer.add( this.refreshButton );
      this.catalogsButtonsSizer.add( this.downloadButton );

      this.catalogsGroup = new GroupBox( this );
      this.catalogsGroup.title = "Catalogs";
      this.catalogsGroup.sizer = new VerticalSizer;
      this.catalogsGroup.sizer.margin = 6;
      this.catalogsGroup.sizer.spacing = 4;
      this.catalogsGroup.sizer.add( this.catalogsBox, 100 );
      this.catalogsGroup.sizer.add( this.catalogsButtonsSizer );

      this.executableSizer = new HorizontalSizer;
      this.executableSizer.spacing = 4;
      this.executableSizer.add( this.executableLabel );
      this.executableSizer.add( this.executableEdit, 100 );
      this.executableSizer.add( this.executableButton );

      this.hintsCheck = new CheckBox( this );
      this.hintsCheck.text = "Use the position and focal length of the image as hints";
      this.hintsCheck.toolTip = "The keywords written by the acquisition software (RA, DEC, FOCALLEN...) order the search. "
         + "The search is blind anyway: a wrong position only costs a fraction of a second.";
      this.hintsCheck.checked = params.useHints;
      this.hintsCheck.onCheck = checked => { this.params.useHints = checked; };

      this.objectsCheck = new CheckBox( this );
      this.objectsCheck.text = "List the deep sky objects of the field";
      this.objectsCheck.checked = params.listObjects;
      this.objectsCheck.onCheck = checked => { this.params.listObjects = checked; };

      this.okButton = new PushButton( this );
      this.okButton.text = "Solve";
      this.okButton.icon = this.scaledResource( ":/icons/ok.png" );
      this.okButton.defaultButton = true;
      this.okButton.onClick = () => { this.ok(); };

      this.cancelButton = new PushButton( this );
      this.cancelButton.text = "Cancel";
      this.cancelButton.icon = this.scaledResource( ":/icons/cancel.png" );
      this.cancelButton.onClick = () => { this.cancel(); };

      this.buttonsSizer = new HorizontalSizer;
      this.buttonsSizer.spacing = 6;
      this.buttonsSizer.addStretch();
      this.buttonsSizer.add( this.okButton );
      this.buttonsSizer.add( this.cancelButton );

      this.sizer = new VerticalSizer;
      this.sizer.margin = 8;
      this.sizer.spacing = 6;
      this.sizer.add( this.help );
      this.sizer.add( this.imageSizer );
      this.sizer.add( this.executableSizer );
      this.sizer.add( this.statusLabel );
      this.sizer.add( this.catalogsGroup, 100 );
      this.sizer.add( this.hintsCheck );
      this.sizer.add( this.objectsCheck );
      this.sizer.addSpacing( 4 );
      this.sizer.add( this.buttonsSizer );

      this.setScaledMinWidth( 560 );
      this.adjustToContents();
      this.downloading = false;
      this.refresh();
   }

   /*
    * Asks the executable for its version and the catalogs, and shows what it answers. A wrong
    * path only shows why nothing runs: the dialog stays usable.
    */
   refresh()
   {
      this.catalogs = null;
      this.catalogsBox.clear();
      let probe = asterionProbe( this.params.executable );
      if ( !probe.ok )
      {
         this.statusLabel.text = "<p style=\"color:#c00000;\">" + probe.error + "</p>";
         this.updateButtons();
         return;
      }
      let catalogs = asterionCatalogs( this.params.executable );
      if ( !catalogs.ok )
      {
         this.statusLabel.text = "<p>" + probe.version + "</p><p style=\"color:#c00000;\">" + catalogs.error + "</p>";
         this.updateButtons();
         return;
      }
      this.catalogs = catalogs;
      this.statusLabel.text = "<p>" + probe.version + ", catalogs in " + catalogs.directory
         + (catalogs.installed.length == 0 ? ". <b>No catalog is installed: download one.</b>" : ".") + "</p>";
      for ( let catalog of catalogs.installed )
      {
         let node = new TreeBoxNode( this.catalogsBox );
         node.setText( 0, catalog.name );
         node.setText( 1, "installed" );
         node.setText( 2, catalog.stars != null
            ? catalog.stars.toLocaleString() + " stars to magnitude " + catalog.magnitudeLimit.toFixed( 1 ) + ", " + (catalog.size / 1048576).toFixed( 0 ) + " MB"
            : "unreadable: " + catalog.error );
         node.catalog = catalog.name;
         node.installed = true;
      }
      for ( let catalog of catalogs.available )
      {
         if ( catalog.installed )
            continue;
         let node = new TreeBoxNode( this.catalogsBox );
         node.setText( 0, catalog.name );
         node.setText( 1, "available" );
         node.setText( 2, catalog.description );
         node.catalog = catalog.name;
         node.installed = false;
      }
      for ( let i = 0; i < 3; ++i )
         this.catalogsBox.adjustColumnWidthToContents( i );
      this.updateButtons();
   }

   /*
    * Downloads and installs the selected catalog, showing the progress in the console.
    */
   download()
   {
      let node = this.catalogsBox.currentNode;
      if ( node == null || node.installed || this.downloading )
         return;
      let name = node.catalog;
      this.downloading = true;
      this.updateButtons();
      this.statusLabel.text = "<p>Downloading " + name + "&hellip; see the console.</p>";
      console.show();
      console.writeln( "<end><cbr><b>Downloading the catalog " + name + "</b>" );
      console.flush();
      let run = asterionRun( this.params.executable, ["--download-catalog", name, "--plain"], 0, text =>
      {
         console.write( text );
         console.flush();
      } );
      this.downloading = false;
      if ( run.ok )
         console.noteln( "<end><cbr>" + name + " is installed." );
      else
      {
         console.criticalln( "<end><cbr>*** " + run.error );
         (new MessageBox( run.error, ASTERION_TITLE, StdIcon_Error, StdButton_Ok )).execute();
      }
      this.refresh();
   }

   /** The image which is selected, or null when there is none. */
   selectedWindow()
   {
      let view = this.imageList.currentView;
      return view.isNull ? null : view.window;
   }

   updateButtons()
   {
      let selected = this.selectedWindow() != null;
      let runs = this.catalogs != null;
      let installed = runs && this.catalogs.installed.length > 0;
      this.okButton.enabled = selected && installed && !this.downloading;
      this.okButton.toolTip = !runs ? "Asterion Solver doesn't run: check the path of the executable."
         : !installed ? "Download a catalog first."
         : !selected ? "Open an image first, and select it." : "";
      let node = this.catalogsBox.currentNode;
      this.downloadButton.enabled = runs && !this.downloading && node != null && !node.installed;
      this.refreshButton.enabled = !this.downloading;
   }
}

function asterionMain()
{
   let params = new AsterionParameters;
   params.load();
   let dialog = new AsterionDialog( params );
   if ( !dialog.execute() )
      return;
   params.save();
   let window = dialog.selectedWindow();
   if ( window == null )
   {
      (new MessageBox( "There is no image to solve: open one, and select it.", ASTERION_TITLE, StdIcon_Error, StdButton_Ok )).execute();
      return;
   }
   try
   {
      asterionSolve( window, params );
   }
   catch ( error )
   {
      console.criticalln( "*** " + error.message );
      (new MessageBox( error.message, ASTERION_TITLE, StdIcon_Error, StdButton_Ok )).execute();
   }
}

#ifndef ASTERION_SOLVER_LIBRARY
asterionMain();
#endif
