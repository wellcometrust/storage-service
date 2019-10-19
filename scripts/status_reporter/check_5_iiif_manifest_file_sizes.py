# -*- encoding: utf-8

import datetime as dt
import json

import aws_client
import check_names
from defaults import defaults
import dynamo_status_manager
import helpers
from id_mapper import IDMapper
from iiif_diff import IIIFDiff
from library_iiif import LibraryIIIF
from matcher import Matcher
import preservica
import reporting


def needs_check(status_summary):
    bnumber = status_summary["bnumber"]
    step_name = "IIIF manifests sizes"

    previous_check = check_names.IIIF_MANIFESTS_CONTENTS
    current_check = check_names.IIIF_MANIFESTS_FILE_SIZES

    if not reporting.has_run_previously(status_summary, previous_check):
        print(f"{step_name} / {bnumber}: previous step has not succeeded")
        return False

    if reporting.has_succeeded_previously(status_summary, current_check):
        if (
            status_summary[previous_check]["last_modified"]
            > status_summary[current_check]["last_modified"]
        ):
            print(f"{step_name} / {bnumber}: previous step is newer than current step")
            return True
        else:
            print(f"{step_name} / {bnumber}: already recorded success")
            return False

    print(f"{step_name} / {bnumber}: no existing result")
    return True


def get_statuses_for_updating(first_bnumber, segment, total_segments):
    reader = dynamo_status_manager.DynamoStatusReader()

    for status_summary in reader.all(first_bnumber, segment, total_segments):
        if needs_check(status_summary):
            yield status_summary


def run_check(status_updater, status_summary):
    bnumber = status_summary["bnumber"]

    s3_client = aws_client.dev_client.s3_client()

    s3_body = s3_client.get_object(
        Bucket="wellcomecollection-storage-infra",
        Key=f"tmp/manifest_diffs/{bnumber}.json",
    )["Body"]

    matcher_result = json.load(s3_body)

    assert not matcher_result["diff"]

    differences = []

    import tqdm

    import random

    CHECK_COUNT = 10

    files_to_check = random.sample(
        matcher_result["files"], min(CHECK_COUNT, len(matcher_result["files"]))
    )

    for f in tqdm.tqdm(files_to_check):
        preservica_size = preservica.get_preservica_asset_size(f["preservica_guid"])
        storage_manifest_size = f["storage_manifest_entry"]["size"]

        if preservica_size != storage_manifest_size:
            differences.append(
                {
                    "guid": f["preservica_guid"],
                    "preservica": preservica_size,
                    "storage_service": storage_manifest_size,
                }
            )

    if differences:
        print(f"Not all file sizes match for {bnumber}: {differences}")
        status_updater.update_status(
            bnumber,
            status_name=check_names.IIIF_MANIFESTS_FILE_SIZES,
            success=False,
            last_modified=dt.datetime.now().isoformat(),
        )
    else:
        print(f"File sizes in IIIF and storage service manifests match for {bnumber}!")
        if len(matcher_result["files"]) > CHECK_COUNT:
            status_updater.update(
                bnumber,
                status_name=check_names.IIIF_MANIFESTS_FILE_SIZES,
                success=True,
                last_modified=dt.datetime.now().isoformat(),
                method=f"only_check_db_row_with_random_sample_{CHECK_COUNT}",
            )
        else:
            status_updater.update(
                bnumber,
                status_name=check_names.IIIF_MANIFESTS_FILE_SIZES,
                success=True,
                last_modified=dt.datetime.now().isoformat(),
            )


def run_one(bnumber):
    with dynamo_status_manager.DynamoStatusUpdater() as status_updater:
        reader = dynamo_status_manager.DynamoStatusReader()
        status_summary = reader.get_one(bnumber)
        if needs_check(status_summary):
            run_check(status_updater, status_summary)


def _run_all(first_bnumber, segment, total_segments):
    with dynamo_status_manager.DynamoStatusUpdater() as status_updater:
        for status_summary in get_statuses_for_updating(
            first_bnumber=first_bnumber, segment=segment, total_segments=total_segments
        ):
            try:
                run_check(status_updater, status_summary)
            except Exception as err:
                print(err)


def run(first_bnumber=None):
    import concurrent.futures
    import multiprocessing

    workers = multiprocessing.cpu_count() * 2 + 1
    total_segments = 5

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [
            executor.submit(_run_all, first_bnumber, segment, total_segments)
            for segment in range(total_segments)
        ]

        for fut in concurrent.futures.as_completed(futures):
            fut.result()


def report(report=None):
    return reporting.build_report(
        name=check_names.IIIF_MANIFESTS_FILE_SIZES, report=report
    )
